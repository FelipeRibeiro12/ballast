package com.felipe.ballast.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class DustCutoffTest {

  @Test
  void dropsBelowFiftyCentsAndKeepsFiftyAndAbove() {
    assertThat(DustCutoff.isDust(new BigDecimal("0.49"))).isTrue();
    assertThat(DustCutoff.isDust(new BigDecimal("0.50"))).isFalse();
    assertThat(DustCutoff.isDust(new BigDecimal("0.51"))).isFalse();
    assertThat(DustCutoff.isDust(BigDecimal.ZERO)).isTrue();
  }

  @Test
  void usesTheAbsoluteValueSoSmallLoansAlsoVanish() {
    assertThat(DustCutoff.isDust(new BigDecimal("-0.30"))).isTrue();
    assertThat(DustCutoff.isDust(new BigDecimal("-0.50"))).isFalse();
    assertThat(DustCutoff.isDust(new BigDecimal("-12"))).isFalse();
  }

  @Test
  void ignoresScaleWhenComparingAndDoesNotRoundUp() {
    assertThat(DustCutoff.isDust(new BigDecimal("0.5000"))).isFalse();
    assertThat(DustCutoff.isDust(new BigDecimal("0.4999"))).isTrue();
  }

  @Test
  void worksInCentsToo() {
    assertThat(DustCutoff.isDustCents(49)).isTrue();
    assertThat(DustCutoff.isDustCents(50)).isFalse();
    assertThat(DustCutoff.isDustCents(-49)).isTrue();
    assertThat(DustCutoff.isDustCents(-50)).isFalse();
    assertThat(DustCutoff.CENTS).isEqualTo(50L);
  }
}
