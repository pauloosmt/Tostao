package com.tostao.tostao_api.imports;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionJavaScript;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;

/** PDFs de teste gerados com o próprio PDFBox: texto selecionável, só imagem, protegido e com ações. */
public final class Pdfs {

	private static final int LINES_PER_PAGE = 50;

	private Pdfs() {
	}

	/** Um PDF com as linhas dadas, uma por linha de texto, distribuídas em páginas de 50 linhas. */
	public static byte[] of(String... lines) {
		return build(false, (String) null, lines);
	}

	/** Um PDF de {@code pages} páginas, cada uma com uma linha de texto. */
	public static byte[] withPages(int pages) {
		try (PDDocument document = new PDDocument()) {
			PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
			for (int i = 0; i < pages; i++) {
				PDPage page = new PDPage(PDRectangle.A4);
				document.addPage(page);
				try (PDPageContentStream content = new PDPageContentStream(document, page)) {
					content.beginText();
					content.setFont(font, 10);
					content.newLineAtOffset(40, 800);
					content.showText("05/11/2026 Compra " + i + " 1,00 D");
					content.endText();
				}
			}
			return save(document);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** Uma página sem nenhum texto (só um retângulo), como um extrato digitalizado. */
	public static byte[] imageOnly() {
		try (PDDocument document = new PDDocument()) {
			PDPage page = new PDPage(PDRectangle.A4);
			document.addPage(page);
			try (PDPageContentStream content = new PDPageContentStream(document, page)) {
				content.addRect(50, 50, 300, 300);
				content.fill();
			}
			return save(document);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** Protegido por senha (RC4/AES de 128 bits), com as linhas dadas. */
	public static byte[] encrypted(String... lines) {
		return build(true, false, lines);
	}

	/** Com um JavaScript de abertura e um link para um endereço externo, que nada deve executar nem abrir. */
	public static byte[] withActions(String... lines) {
		return build(false, "http://127.0.0.1:9/nunca-deve-abrir", lines);
	}

	/** Como {@link #withActions(String...)}, com o endereço do link escolhido (para o teste vigiar o endereço). */
	public static byte[] withLinkTo(String uri, String... lines) {
		return build(false, uri, lines);
	}

	private static byte[] build(boolean encrypt, boolean actions, String... lines) {
		return build(encrypt, actions ? "http://127.0.0.1:9/nunca-deve-abrir" : null, lines);
	}

	private static byte[] build(boolean encrypt, String linkUri, String... lines) {
		boolean actions = linkUri != null;
		try (PDDocument document = new PDDocument()) {
			PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
			List<List<String>> pages = new ArrayList<>();
			for (int from = 0; from < Math.max(lines.length, 1); from += LINES_PER_PAGE) {
				pages.add(List.of(lines).subList(Math.min(from, lines.length), Math.min(from + LINES_PER_PAGE, lines.length)));
			}
			for (List<String> pageLines : pages) {
				PDPage page = new PDPage(PDRectangle.A4);
				document.addPage(page);
				try (PDPageContentStream content = new PDPageContentStream(document, page)) {
					content.beginText();
					content.setFont(font, 10);
					content.setLeading(13);
					content.newLineAtOffset(40, 800);
					for (String line : pageLines) {
						content.showText(line);
						content.newLine();
					}
					content.endText();
				}
				if (actions) {
					PDAnnotationLink link = new PDAnnotationLink();
					link.setRectangle(new PDRectangle(10, 10, 100, 20));
					PDActionURI uri = new PDActionURI();
					uri.setURI(linkUri);
					link.setAction(uri);
					page.getAnnotations().add(link);
				}
			}
			if (actions) {
				document.getDocumentCatalog().setOpenAction(new PDActionJavaScript("app.alert('nao deve executar');"));
			}
			if (encrypt) {
				StandardProtectionPolicy policy = new StandardProtectionPolicy("dono", "usuario", new AccessPermission());
				policy.setEncryptionKeyLength(128);
				document.protect(policy);
			}
			return save(document);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static byte[] save(PDDocument document) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		document.save(out);
		return out.toByteArray();
	}

}
