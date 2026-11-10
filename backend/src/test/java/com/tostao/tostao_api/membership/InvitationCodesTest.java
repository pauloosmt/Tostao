package com.tostao.tostao_api.membership;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** O código do convite e a sua impressão: tamanho, alfabeto, entropia, unicidade e vetor conhecido. */
class InvitationCodesTest {

	@Test
	void aCodeHas43UrlSafeCharactersWithoutPaddingOrSymbols() {
		for (int i = 0; i < 200; i++) {
			String code = InvitationCodes.generate();

			assertThat(code).hasSize(43).matches("[A-Za-z0-9_-]{43}");
			assertThat(code).doesNotContain("=", "+", "/");
		}
	}

	@Test
	void aCodeCarries256BitsThatDecodeBackToThirtyTwoBytes() {
		String code = InvitationCodes.generate();

		byte[] bytes = Base64.getUrlDecoder().decode(code);

		assertThat(bytes).hasSize(32);
	}

	@Test
	void tenThousandCodesNeverRepeat() {
		Set<String> seen = new HashSet<>();
		for (int i = 0; i < 10_000; i++) {
			assertThat(seen.add(InvitationCodes.generate())).as("repetiu na posição %d", i).isTrue();
		}
	}

	@Test
	void theBytesAreNotAllTheSameOrObviouslyBiased() {
		// um teste grosseiro de que não é constante: em 1.000 códigos, cada posição do primeiro byte varia
		Set<Byte> firstBytes = new HashSet<>();
		for (int i = 0; i < 1_000; i++) {
			firstBytes.add(Base64.getUrlDecoder().decode(InvitationCodes.generate())[0]);
		}
		assertThat(firstBytes.size()).isGreaterThan(150);
	}

	@Test
	void theHashIsSixtyFourLowercaseHexCharacters() {
		String hash = InvitationCodes.hash(InvitationCodes.generate());

		assertThat(hash).hasSize(64).matches("[0-9a-f]{64}");
	}

	@Test
	void theHashMatchesAKnownSha256Vector() {
		assertThat(InvitationCodes.hash("abc"))
				.isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
		assertThat(InvitationCodes.hash(""))
				.isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
	}

	@Test
	void theHashIsDeterministicAndChangesWithASingleCharacter() {
		String code = InvitationCodes.generate();
		String other = code.substring(0, 42) + (code.charAt(42) == 'A' ? 'B' : 'A');

		assertThat(InvitationCodes.hash(code)).isEqualTo(InvitationCodes.hash(code));
		assertThat(InvitationCodes.hash(other)).isNotEqualTo(InvitationCodes.hash(code));
	}

	@Test
	void theHashNeverContainsTheCode() {
		String code = InvitationCodes.generate();

		assertThat(InvitationCodes.hash(code)).doesNotContain(code).doesNotContain(code.substring(0, 8));
	}

	@Test
	void anyInputHasAHashAndNullDoesNotCrash() {
		assertThat(InvitationCodes.hash(null)).hasSize(64);
		assertThat(InvitationCodes.hash("x".repeat(100_000))).hasSize(64);
		assertThat(InvitationCodes.hash("código com acento ç ã")).hasSize(64);
	}

}
