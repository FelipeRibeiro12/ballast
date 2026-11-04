package com.felipe.ballast.wallets;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WalletSummaryTest {

  private static final UUID A = UUID.randomUUID();
  private static final UUID B = UUID.randomUUID();
  private static final BigDecimal RATE = new BigDecimal("5.00");

  private static BigDecimal d(String value) {
    return new BigDecimal(value);
  }

  private static WalletSummary.Token token(UUID wallet, String qty, String price, String ago) {
    return new WalletSummary.Token(wallet, d(qty), d(price), ago == null ? null : d(ago));
  }

  private static WalletSummary.Input wallet(UUID id) {
    return new WalletSummary.Input(id, WalletNetwork.ETHEREUM, "w");
  }

  private static WalletSummary compute(
      List<WalletSummary.Token> tokens, List<WalletSummary.Defi> defi, BigDecimal rate) {
    return WalletSummary.compute(List.of(wallet(A), wallet(B)), tokens, defi, rate);
  }

  @Test
  void dropsTokensBelowTheCutoffAndKeepsFiftyCents() {
    WalletSummary summary =
        compute(
            List.of(
                token(A, "1", "0.49", "0.49"),
                token(A, "1", "0.50", "0.50"),
                token(A, "1", "0.51", "0.51")),
            List.of(),
            RATE);
    assertThat(summary.wallets().get(0).figures().valueUsdCents()).isEqualTo(101L);
  }

  @Test
  void dustIsMeasuredOnTheFullValueNotOnTheRoundedCents() {
    WalletSummary summary = compute(List.of(token(A, "1", "0.499", "0.499")), List.of(), RATE);
    assertThat(summary.wallets().get(0).figures().valueUsdCents()).isZero();
  }

  @Test
  void dropsDefiBelowTheCutoffInModuleButKeepsTheBorderAndLoans() {
    WalletSummary summary =
        compute(
            List.of(),
            List.of(
                new WalletSummary.Defi(A, 49L, 1L),
                new WalletSummary.Defi(A, -30L, 1L),
                new WalletSummary.Defi(A, 50L, 5L),
                new WalletSummary.Defi(A, -5000L, -100L),
                new WalletSummary.Defi(A, 12000L, 200L)),
            RATE);
    WalletSummary.Figures figures = summary.wallets().get(0).figures();
    assertThat(figures.valueUsdCents()).isEqualTo(7050L);
    assertThat(figures.change24hUsdCents()).isEqualTo(105L);
    assertThat(figures.partialChange()).isFalse();
  }

  @Test
  void loanReducesTheWalletValue() {
    WalletSummary summary =
        compute(
            List.of(token(A, "10", "100", "100")),
            List.of(new WalletSummary.Defi(A, -30000L, null)),
            RATE);
    assertThat(summary.wallets().get(0).figures().valueUsdCents()).isEqualTo(70000L);
  }

  @Test
  void changeIsQuantityTimesThePriceDifference() {
    WalletSummary summary = compute(List.of(token(A, "2", "110", "100")), List.of(), RATE);
    WalletSummary.Figures figures = summary.wallets().get(0).figures();
    assertThat(figures.valueUsdCents()).isEqualTo(22000L);
    assertThat(figures.change24hUsdCents()).isEqualTo(2000L);
    assertThat(figures.change24hPercent()).isEqualTo("10.00");
    assertThat(figures.partialChange()).isFalse();
  }

  @Test
  void negativeChangeGivesNegativePercent() {
    WalletSummary summary = compute(List.of(token(A, "1", "90", "100")), List.of(), RATE);
    WalletSummary.Figures figures = summary.wallets().get(0).figures();
    assertThat(figures.change24hUsdCents()).isEqualTo(-1000L);
    assertThat(figures.change24hPercent()).isEqualTo("-10.00");
  }

  @Test
  void tokenWithoutAnOldQuoteStaysOutOfTheChangeAndMarksPartial() {
    WalletSummary summary =
        compute(List.of(token(A, "1", "100", "90"), token(A, "1", "50", null)), List.of(), RATE);
    WalletSummary.Figures figures = summary.wallets().get(0).figures();
    assertThat(figures.valueUsdCents()).isEqualTo(15000L);
    assertThat(figures.change24hUsdCents()).isEqualTo(1000L);
    assertThat(figures.partialChange()).isTrue();
    assertThat(summary.total().partialChange()).isTrue();
    assertThat(summary.wallets().get(1).figures().partialChange()).isFalse();
  }

  @Test
  void defiWithoutChangeMarksPartial() {
    WalletSummary summary =
        compute(List.of(), List.of(new WalletSummary.Defi(A, 10000L, null)), RATE);
    assertThat(summary.wallets().get(0).figures().partialChange()).isTrue();
  }

  @Test
  void percentIsNullWhenTheBaseIsZeroOrNegative() {
    assertThat(WalletSummary.percent(0L, 0L)).isNull();
    assertThat(WalletSummary.percent(1000L, 1000L)).isNull();
    assertThat(WalletSummary.percent(500L, 1000L)).isNull();
    assertThat(WalletSummary.percent(-300L, -100L)).isNull();
    assertThat(WalletSummary.percent(100L, 110L)).isNull();
  }

  @Test
  void percentUsesTheValueOf24HoursAgoAsBaseWithTwoPlaces() {
    assertThat(WalletSummary.percent(11000L, 1000L)).isEqualTo("10.00");
    assertThat(WalletSummary.percent(10000L, 1L)).isEqualTo("0.01");
    assertThat(WalletSummary.percent(100L, 0L)).isEqualTo("0.00");
    // 100 / 300 = 33.333...
    assertThat(WalletSummary.percent(400L, 100L)).isEqualTo("33.33");
  }

  @Test
  void emptyWalletIsAllZeroWithNullPercent() {
    WalletSummary summary = compute(List.of(), List.of(), RATE);
    WalletSummary.Figures figures = summary.wallets().get(0).figures();
    assertThat(figures.valueUsdCents()).isZero();
    assertThat(figures.valueBrlCents()).isZero();
    assertThat(figures.change24hPercent()).isNull();
    assertThat(figures.partialChange()).isFalse();
  }

  @Test
  void realIsDollarTimesRateRoundedOncePerWalletAndTheTotalAddsTheRoundedWallets() {
    // A: 0,50 x 1,11 = 55,5 centavos, o meio vai para o par (56). B: 1,01 x 1,11 = 112,11 (112).
    // O total soma as carteiras já arredondadas: 56 + 112.
    WalletSummary summary =
        WalletSummary.compute(
            List.of(wallet(A), wallet(B)),
            List.of(),
            List.of(
                new WalletSummary.Defi(A, 50L, 0L),
                new WalletSummary.Defi(B, 50L, 0L),
                new WalletSummary.Defi(B, 51L, 0L)),
            d("1.11"));
    long sum = summary.wallets().stream().mapToLong(w -> w.figures().valueBrlCents()).sum();
    assertThat(summary.total().valueBrlCents()).isEqualTo(sum);
    long usdSum = summary.wallets().stream().mapToLong(w -> w.figures().valueUsdCents()).sum();
    assertThat(summary.total().valueUsdCents()).isEqualTo(usdSum).isEqualTo(151L);
    assertThat(summary.wallets().get(0).figures().valueBrlCents()).isEqualTo(56L);
    assertThat(summary.wallets().get(1).figures().valueBrlCents()).isEqualTo(112L);
    assertThat(summary.total().valueBrlCents()).isEqualTo(168L);
  }

  @Test
  void withoutTheDollarRateRealIsNullAndUsdStays() {
    WalletSummary summary = compute(List.of(token(A, "2", "110", "100")), List.of(), null);
    WalletSummary.Figures figures = summary.wallets().get(0).figures();
    assertThat(figures.valueBrlCents()).isNull();
    assertThat(figures.change24hBrlCents()).isNull();
    assertThat(figures.valueUsdCents()).isEqualTo(22000L);
    assertThat(summary.total().valueBrlCents()).isNull();
    assertThat(summary.total().change24hBrlCents()).isNull();
  }

  @Test
  void totalChangeAndPercentComeFromTheSumsOfTheWallets() {
    WalletSummary summary =
        compute(List.of(token(A, "1", "110", "100"), token(B, "1", "190", "200")), List.of(), RATE);
    assertThat(summary.total().valueUsdCents()).isEqualTo(30000L);
    assertThat(summary.total().change24hUsdCents()).isZero();
    assertThat(summary.total().change24hPercent()).isEqualTo("0.00");
    assertThat(summary.total().change24hBrlCents()).isZero();
  }

  @Test
  void tokenWithoutPriceIsLeftOutOfTheValue() {
    WalletSummary summary =
        WalletSummary.compute(
            List.of(wallet(A)),
            List.of(new WalletSummary.Token(A, d("5"), null, null)),
            List.of(),
            RATE);
    assertThat(summary.wallets().get(0).figures().valueUsdCents()).isZero();
  }
}
