package com.felipe.ballast.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TotpTest {

  // Chave dos vetores de teste SHA-1 do apêndice B da RFC 6238.
  private static final byte[] RFC_KEY = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

  // A RFC dá 8 dígitos; os 6 de baixo são os do autenticador.
  @ParameterizedTest
  @CsvSource({
    "59, 287082",
    "1111111109, 081804",
    "1111111111, 050471",
    "1234567890, 005924",
    "2000000000, 279037",
    "20000000000, 353130"
  })
  void matchesTheRfc6238Vectors(long epochSeconds, String code) {
    assertThat(Totp.codeAt(RFC_KEY, Totp.stepOf(Instant.ofEpochSecond(epochSeconds))))
        .isEqualTo(code);
  }

  @Test
  void acceptsTheCodeOfThePreviousAndNextStepForClockDrift() {
    Instant now = Instant.ofEpochSecond(1111111111);
    long step = Totp.stepOf(now);

    assertThat(Totp.match(RFC_KEY, Totp.codeAt(RFC_KEY, step - 1), now)).hasValue(step - 1);
    assertThat(Totp.match(RFC_KEY, Totp.codeAt(RFC_KEY, step), now)).hasValue(step);
    assertThat(Totp.match(RFC_KEY, Totp.codeAt(RFC_KEY, step + 1), now)).hasValue(step + 1);
    assertThat(Totp.match(RFC_KEY, Totp.codeAt(RFC_KEY, step + 2), now)).isEmpty();
    assertThat(Totp.match(RFC_KEY, Totp.codeAt(RFC_KEY, step - 2), now)).isEmpty();
  }

  @Test
  void refusesAnythingThatIsNotSixDigits() {
    Instant now = Instant.ofEpochSecond(59);
    assertThat(Totp.match(RFC_KEY, "28708", now)).isEqualTo(OptionalLong.empty());
    assertThat(Totp.match(RFC_KEY, "2870820", now)).isEmpty();
    assertThat(Totp.match(RFC_KEY, "28708a", now)).isEmpty();
    assertThat(Totp.match(RFC_KEY, "２８７０８２", now)).isEmpty();
  }

  @Test
  void newSecretsHaveTwentyBytesAndDiffer() {
    assertThat(Totp.newSecret()).hasSize(20).isNotEqualTo(Totp.newSecret());
  }

  // Vetor da RFC 4648, seção 10, em minúsculas para o autenticador e sem "=" no fim.
  @Test
  void encodesBase32WithoutPadding() {
    assertThat(Totp.base32("foobar".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MZXW6YTBOI");
    assertThat(Totp.base32(RFC_KEY)).isEqualTo("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
  }
}
