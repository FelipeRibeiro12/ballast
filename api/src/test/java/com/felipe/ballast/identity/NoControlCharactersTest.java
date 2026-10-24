package com.felipe.ballast.identity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

// Valida o texto direto, sem o contexto do Spring: o validador é o mesmo em todos os campos.
class NoControlCharactersTest {

  private final NoControlCharacters.Validator validator = new NoControlCharacters.Validator();

  private boolean isValid(String text) {
    return validator.isValid(text, null);
  }

  @Test
  void acceptsAssignedCodePointsWhoseLow16BitsAreInTheSurrogateRange() {
    // Os 16 bits de baixo de U+1D800 são D800: o cast para char o confundia com surrogate.
    assertThat(isValid(new String(Character.toChars(0x1D800)))).isTrue();
    assertThat(isValid("a" + new String(Character.toChars(0x1DFFF)) + "b")).isTrue();
    assertThat(isValid(new String(Character.toChars(0x10D800)))).isTrue();
  }

  @Test
  void acceptsEmojiAndAccents() {
    assertThat(isValid("café 😀")).isTrue();
  }

  @ParameterizedTest
  @ValueSource(chars = {'\ud800', '\udbff', '\udc00', '\udfff'})
  void rejectsALoneSurrogate(char surrogate) {
    assertThat(isValid("a" + surrogate + "b")).isFalse();
  }

  @ParameterizedTest
  @ValueSource(strings = {"\u0000", "\u0007", "\n", "\t", "\u007f", "\u0085", " ", " "})
  void rejectsControlCharacters(String control) {
    assertThat(isValid("a" + control + "b")).isFalse();
  }

  @Test
  void acceptsNull() {
    assertThat(isValid(null)).isTrue();
  }

  // Cf é invisível ou muda a direção do texto: "Conta\u200b" parece igual a "Conta", e U+202E
  // inverte o que vem depois. Os tags U+E0000 a U+E007F escondem texto inteiro.
  @ParameterizedTest
  @ValueSource(ints = {0x200B, 0x200E, 0x202A, 0x202E, 0x2066, 0x2060, 0xFEFF, 0x00AD, 0xE0041})
  void rejectsFormatCharacters(int codePoint) {
    assertThat(isValid("a" + new String(Character.toChars(codePoint)) + "b")).isFalse();
  }

  // ZWJ e ZWNJ são Cf, mas sem eles o emoji de família quebra em pedaços e o persa perde a
  // grafia correta. Ficam de fora da regra nos nomes (o e-mail recusa todos, em BasicEmail).
  @Test
  void acceptsJoinersNeededByEmojiSequencesAndPersian() {
    assertThat(isValid("👨\u200d👩\u200d👧")).isTrue();
    assertThat(isValid("می\u200cخواهم")).isTrue();
  }
}
