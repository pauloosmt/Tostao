package com.tostao.tostao_api.imports;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;

import com.tostao.tostao_api.common.error.FieldValidationException;

/**
 * Lê os movimentos de um PDF de extrato com texto selecionável. O PDF não tem campos: o texto é extraído (PDFBox, só em
 * memória) e cada linha que <b>começa com uma data</b> e <b>termina com um valor</b> é um movimento. O documento é só
 * dado: nada dele é executado (scripts, ações, anexos) nem aberto (links), e o consumo é limitado em páginas, em texto e
 * em tempo. Nenhuma mensagem cita o conteúdo.
 *
 * <p>
 * Datas {@code dd/MM/yyyy}, {@code dd/MM/yy} ou {@code dd/MM} (o ano é o da primeira data completa do documento). O valor
 * tem duas casas decimais, com os separadores brasileiros ou internacionais, e o sinal vem no começo ({@code -}), no fim
 * ({@code -}) ou pela letra {@code D} (débito) ou {@code C} (crédito) depois do número. Linhas de saldo e linhas que não
 * casam com o padrão são ignoradas.
 */
final class PdfReader {

	static final int MAX_PAGES = 20;
	static final int MAX_TEXT = 500_000;
	static final Duration TIME_LIMIT = Duration.ofSeconds(15);

	private static final Pattern FULL_DATE = Pattern.compile("(?<![\\d/])(\\d{2})/(\\d{2})/(\\d{4})(?![\\d/])");
	private static final Pattern MOVEMENT = Pattern.compile(
			"^(\\d{2})/(\\d{2})(?:/(\\d{4}|\\d{2}))?\\s+(.*?)\\s+"
					+ "((?:[-+]\\s?)?(?:R\\$\\s?)?(?:[-+]\\s?)?\\d[\\d.,]*[.,]\\d{2})(?:\\s?(-|[DdCc]))?$");

	/** Poucas threads: a extração de um PDF hostil nunca ocupa mais que isto, e termina por tempo. */
	private static final ExecutorService EXTRACTORS = Executors.newFixedThreadPool(2, runnable -> {
		Thread thread = new Thread(runnable, "pdf-extractor");
		thread.setDaemon(true);
		return thread;
	});

	private PdfReader() {
	}

	static boolean looksLikePdf(byte[] bytes) {
		int limit = Math.min(bytes.length - 5, 1024);
		for (int i = 0; i <= limit; i++) {
			if (bytes[i] == '%' && bytes[i + 1] == 'P' && bytes[i + 2] == 'D' && bytes[i + 3] == 'F' && bytes[i + 4] == '-') {
				return true;
			}
		}
		return false;
	}

	static List<RawLine> read(byte[] bytes) {
		return read(bytes, TIME_LIMIT, PdfReader::extractText);
	}

	/** O extrator e o limite são parâmetros para os testes provarem o limite de tempo. */
	static List<RawLine> read(byte[] bytes, Duration limit, Function<byte[], String> extractor) {
		return movements(extractWithin(bytes, limit, extractor));
	}

	private static String extractWithin(byte[] bytes, Duration limit, Function<byte[], String> extractor) {
		Future<String> future;
		try {
			future = EXTRACTORS.submit(() -> extractor.apply(bytes));
		}
		catch (RejectedExecutionException e) {
			throw refuse("o servidor está ocupado; tente de novo");
		}
		try {
			return future.get(limit.toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (TimeoutException e) {
			future.cancel(true);
			throw refuse("o PDF demorou demais para ser lido");
		}
		catch (InterruptedException e) {
			future.cancel(true);
			Thread.currentThread().interrupt();
			throw refuse("a leitura do PDF foi interrompida");
		}
		catch (ExecutionException e) {
			if (e.getCause() instanceof FieldValidationException refusal) {
				throw refusal;
			}
			throw refuse("não foi possível ler o PDF");
		}
	}

	/** O texto do documento, página a página, na ordem de leitura. Recusa o que não dá para ler como extrato. */
	static String extractText(byte[] bytes) {
		try (PDDocument document = Loader.loadPDF(new RandomAccessReadBuffer(bytes), "", null, null,
				IOUtils.createMemoryOnlyStreamCache())) {
			if (document.isEncrypted()) {
				throw refuse("o PDF está protegido por senha");
			}
			if (document.getNumberOfPages() > MAX_PAGES) {
				throw refuse("o PDF tem mais de " + MAX_PAGES + " páginas");
			}
			PDFTextStripper stripper = new PDFTextStripper();
			stripper.setSortByPosition(true);
			String text = stripper.getText(document);
			if (text.length() > MAX_TEXT) {
				throw refuse("o PDF tem texto demais");
			}
			if (text.isBlank()) {
				throw refuse("o PDF não tem texto selecionável (talvez seja uma imagem)");
			}
			return text;
		}
		catch (InvalidPasswordException e) {
			throw refuse("o PDF está protegido por senha");
		}
		catch (IOException e) {
			throw refuse("não foi possível ler o PDF");
		}
	}

	/** Os movimentos do texto: linhas que começam com data e terminam com valor, menos as de saldo. */
	static List<RawLine> movements(String text) {
		Integer documentYear = firstFullYear(text);
		List<RawLine> lines = new ArrayList<>();
		String[] rows = text.split("\\r?\\n|\\r");
		for (int i = 0; i < rows.length; i++) {
			String row = rows[i].replaceAll("[\\s\\u00A0]+", " ").strip();
			Matcher match = MOVEMENT.matcher(row);
			if (!match.matches()) {
				continue;
			}
			String description = match.group(4);
			if (ImportKeys.normalize(description).startsWith("saldo")) {
				continue;
			}
			List<String> problems = new ArrayList<>();
			LocalDate date = date(match.group(1), match.group(2), match.group(3), documentYear, problems);
			Long cents = null;
			Money.Result money = Money.csv(match.group(5));
			if (money.problem() != null) {
				problems.add(money.problem());
			}
			else {
				cents = signed(money.cents(), match.group(6));
			}
			lines.add(new RawLine(i + 1, date, description, cents, null, problems));
		}
		return lines;
	}

	/** O sinal do valor: o do próprio número (se negativo) ou o do sufixo {@code -}/{@code D} (negativo) e {@code C}. */
	private static long signed(long cents, String suffix) {
		boolean debit = suffix != null && (suffix.equals("-") || suffix.equalsIgnoreCase("D"));
		return debit && cents > 0 ? -cents : cents;
	}

	private static Integer firstFullYear(String text) {
		Matcher match = FULL_DATE.matcher(text);
		while (match.find()) {
			int month = Integer.parseInt(match.group(2));
			int day = Integer.parseInt(match.group(1));
			if (month >= 1 && month <= 12 && day >= 1 && day <= 31) {
				return Integer.parseInt(match.group(3));
			}
		}
		return null;
	}

	private static LocalDate date(String day, String month, String year, Integer documentYear, List<String> problems) {
		int y;
		if (year == null) {
			if (documentYear == null) {
				problems.add("ano não identificado");
				return null;
			}
			y = documentYear;
		}
		else {
			y = year.length() == 2 ? 2000 + Integer.parseInt(year) : Integer.parseInt(year);
		}
		int m = Integer.parseInt(month);
		int d = Integer.parseInt(day);
		if (m < 1 || m > 12 || d < 1 || d > YearMonth.of(y, m).lengthOfMonth()) {
			problems.add("data inválida");
			return null;
		}
		return LocalDate.of(y, m, d);
	}

	private static FieldValidationException refuse(String reason) {
		return new FieldValidationException("file", reason);
	}

}
