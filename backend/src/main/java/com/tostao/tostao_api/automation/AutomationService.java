package com.tostao.tostao_api.automation;

import java.time.LocalDate;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A execução automática (sem pessoa logada): gera as ocorrências das recorrências que faltam e depois efetiva o que
 * venceu. Faz as duas coisas chamando as funções de banco {@code generate_recurrence_occurrences} e
 * {@code auto_clear_due} (migration {@code V11}), que são em lote e só produzem o que a regra descreve; por isso o
 * RLS não precisa de contexto de pessoa aqui. A ordem importa: uma ocorrência gerada já vencida, com o marcador
 * ligado, é efetivada na mesma execução.
 */
@Service
public class AutomationService {

	/** Chave da trava consultiva do Postgres: só uma execução por vez faz o trabalho. */
	public static final long LOCK_KEY = 20261110L;

	/** O que uma execução fez. {@code skipped} é verdadeiro quando outra execução estava em curso. */
	public record RunResult(boolean skipped, int generated, int cleared) {
	}

	private final JdbcClient jdbc;

	public AutomationService(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Uma execução, numa transação. A trava consultiva só evita trabalho repetido (outra execução em curso faz esta ser
	 * pulada): a correção não depende dela, porque a geração é idempotente (índice único) e a efetivação é condicional
	 * (pendente, com o marcador). Nenhuma exceção é capturada aqui: uma falha desfaz a execução inteira e a próxima
	 * tenta de novo.
	 */
	@Transactional
	public RunResult runOnce(LocalDate today) {
		boolean locked = jdbc.sql("select pg_try_advisory_xact_lock(:key)").param("key", LOCK_KEY)
				.query(Boolean.class).single();
		if (!locked) {
			return new RunResult(true, 0, 0);
		}
		int generated = jdbc.sql("select generate_recurrence_occurrences(cast(:today as date), null)")
				.param("today", today).query(Integer.class).single();
		int cleared = jdbc.sql("select auto_clear_due(cast(:today as date))").param("today", today)
				.query(Integer.class).single();
		return new RunResult(false, generated, cleared);
	}

}
