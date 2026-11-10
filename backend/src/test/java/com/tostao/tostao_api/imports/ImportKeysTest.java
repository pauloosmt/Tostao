package com.tostao.tostao_api.imports;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

/** A chave de importação: estável, sem diferença de caixa, acentos e espaços, e sempre cabendo em 200 caracteres. */
class ImportKeysTest {

	private static final LocalDate DAY = LocalDate.of(2026, 11, 6);

	@Test
	void normalizeLowersRemovesAccentsAndCollapsesSpaces() {
		assertThat(ImportKeys.normalize("  PADARIA   do Zé\t\n")).isEqualTo("padaria do ze");
		assertThat(ImportKeys.normalize("Açaí & Ação")).isEqualTo("acai & acao");
		assertThat(ImportKeys.normalize("")).isEmpty();
	}

	@Test
	void anIdentifierMakesTheKeyAndABlankOneMakesNone() {
		assertThat(ImportKeys.fromIdentifier("202611060001")).isEqualTo("id:202611060001");
		assertThat(ImportKeys.fromIdentifier("  A1  ")).isEqualTo("id:A1");
		assertThat(ImportKeys.fromIdentifier("")).isNull();
		assertThat(ImportKeys.fromIdentifier("   ")).isNull();
		assertThat(ImportKeys.fromIdentifier(null)).isNull();
	}

	@Test
	void aLongIdentifierBecomesItsHashAndAlwaysFitsTheLimit() {
		String raw = "x".repeat(150);
		String longer = "x".repeat(151);
		String huge = "y".repeat(5000);

		assertThat(ImportKeys.fromIdentifier(raw)).isEqualTo("id:" + raw).hasSize(153);
		assertThat(ImportKeys.fromIdentifier(longer)).startsWith("id:h:").hasSize(5 + 64);
		assertThat(ImportKeys.fromIdentifier(huge)).hasSize(5 + 64);
		assertThat(ImportKeys.fromIdentifier(longer)).isEqualTo(ImportKeys.fromIdentifier(longer))
				.isNotEqualTo(ImportKeys.fromIdentifier(huge));
	}

	@Test
	void theDerivedKeyIsStableAndIgnoresCaseAccentsAndSpaces() {
		String key = ImportKeys.derived(DAY, -2350, "Padaria do Zé", 0);

		assertThat(key).startsWith("h:").hasSize(2 + 64).matches("h:[0-9a-f]{64}");
		assertThat(ImportKeys.derived(DAY, -2350, "Padaria do Zé", 0)).isEqualTo(key);
		assertThat(ImportKeys.derived(DAY, -2350, "  PADARIA   DO ZE ", 0)).isEqualTo(key);
	}

	@Test
	void everyPartOfTheMovementChangesTheDerivedKey() {
		String key = ImportKeys.derived(DAY, -2350, "Padaria", 0);

		assertThat(ImportKeys.derived(DAY.plusDays(1), -2350, "Padaria", 0)).isNotEqualTo(key);
		assertThat(ImportKeys.derived(DAY, -2351, "Padaria", 0)).isNotEqualTo(key);
		assertThat(ImportKeys.derived(DAY, 2350, "Padaria", 0)).isNotEqualTo(key);
		assertThat(ImportKeys.derived(DAY, -2350, "Padaria 2", 0)).isNotEqualTo(key);
		assertThat(ImportKeys.derived(DAY, -2350, "Padaria", 1)).isNotEqualTo(key);
	}

	@Test
	void anIdentifierKeyAndADerivedKeyNeverCollide() {
		assertThat(ImportKeys.fromIdentifier("h:abc")).isEqualTo("id:h:abc").isNotEqualTo(ImportKeys.derived(DAY, 1, "a", 0));
	}

}
