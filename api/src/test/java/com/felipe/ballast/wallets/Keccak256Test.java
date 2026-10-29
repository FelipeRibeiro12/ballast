package com.felipe.ballast.wallets;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class Keccak256Test {

  private static String hex(String text) {
    return HexFormat.of().formatHex(Keccak256.hash(text.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void hashesTheEmptyStringToTheKnownVector() {
    assertThat(hex(""))
        .isEqualTo("c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470");
  }

  @Test
  void hashesShortTextToTheKnownVector() {
    assertThat(hex("abc"))
        .isEqualTo("4e03657aea45a94fc7d47ba826c8d667c0d1e6e33a64a036ec44f58fa12d6c45");
  }

  @Test
  void hashesInputLongerThanOneBlock() {
    // 136 bytes é exatamente a taxa; 200 passa de um bloco e o resto vai para o segundo.
    assertThat(hex("a".repeat(136))).hasSize(64).isNotEqualTo(hex("a".repeat(135)));
    assertThat(hex("a".repeat(200))).hasSize(64).isNotEqualTo(hex("a".repeat(136)));
  }
}
