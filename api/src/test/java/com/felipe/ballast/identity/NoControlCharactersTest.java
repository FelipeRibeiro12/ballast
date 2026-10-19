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
}
