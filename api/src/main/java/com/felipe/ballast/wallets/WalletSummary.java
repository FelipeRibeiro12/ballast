package com.felipe.ballast.wallets;

import com.felipe.ballast.crypto.DustCutoff;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// Conta pura do valor e da variação de 24 h por carteira (ADR-082). Sem banco: recebe os saldos
// com os preços já em dólar e as posições DeFi já cortadas na consulta.
record WalletSummary(List<Wallet> wallets, Figures total) {

  record Input(UUID id, WalletNetwork network, String label) {}

  // priceUsd nulo: sem cotação, o saldo não entra. priceAgoUsd nulo: sem cotação com 24 h de
  // idade, o saldo fica fora da variação.
  record Token(UUID walletId, BigDecimal quantity, BigDecimal priceUsd, BigDecimal priceAgoUsd) {}

  record Defi(UUID walletId, long valueUsdCents, Long change24hUsdCents) {}

  record Figures(
      long valueUsdCents,
      Long valueBrlCents,
      long change24hUsdCents,
      Long change24hBrlCents,
      String change24hPercent,
      boolean partialChange) {}

  record Wallet(Input wallet, Figures figures) {}

  // Teto do inteiro seguro do JavaScript, em dólares: acima disso o dado é de terceiro absurdo e
  // o item fica fora em vez de estourar o centavo.
  private static final BigDecimal MAX_ITEM_USD = new BigDecimal("10000000000000");
  private static final long MAX_SAFE_CENTS = 9_007_199_254_740_991L;
  private static final BigDecimal HUNDRED = new BigDecimal("100");

  static WalletSummary compute(
      List<Input> wallets, List<Token> tokens, List<Defi> defi, BigDecimal brlRate) {
    List<Wallet> out = new ArrayList<>();
    long usd = 0;
    long usdChange = 0;
    Long brl = brlRate == null ? null : 0L;
    Long brlChange = brlRate == null ? null : 0L;
    boolean partial = false;
    for (Input wallet : wallets) {
      Figures figures = of(wallet.id(), tokens, defi, brlRate);
      out.add(new Wallet(wallet, figures));
      usd += figures.valueUsdCents();
      usdChange += figures.change24hUsdCents();
      if (brlRate != null) {
        brl += figures.valueBrlCents();
        brlChange += figures.change24hBrlCents();
      }
      partial |= figures.partialChange();
    }
    // O total soma os centavos já arredondados de cada carteira, para a coluna fechar na tela.
    Figures total = new Figures(usd, brl, usdChange, brlChange, percent(usd, usdChange), partial);
    return new WalletSummary(List.copyOf(out), total);
  }

  private static Figures of(UUID walletId, List<Token> tokens, List<Defi> defi, BigDecimal rate) {
    BigDecimal value = BigDecimal.ZERO;
    BigDecimal change = BigDecimal.ZERO;
    boolean partial = false;
    for (Token token : tokens) {
      if (!token.walletId().equals(walletId) || token.priceUsd() == null) {
        continue;
      }
      BigDecimal tokenValue = token.quantity().multiply(token.priceUsd());
      if (DustCutoff.isDust(tokenValue)) {
        continue;
      }
      if (tokenValue.abs().compareTo(MAX_ITEM_USD) > 0) {
        partial = true;
        continue;
      }
      value = value.add(tokenValue);
      if (token.priceAgoUsd() == null) {
        partial = true;
      } else {
        change =
            change.add(token.quantity().multiply(token.priceUsd().subtract(token.priceAgoUsd())));
      }
    }
    for (Defi position : defi) {
      if (!position.walletId().equals(walletId)
          || DustCutoff.isDustCents(position.valueUsdCents())) {
        continue;
      }
      value = value.add(cents(position.valueUsdCents()));
      if (position.change24hUsdCents() == null) {
        partial = true;
      } else {
        change = change.add(cents(position.change24hUsdCents()));
      }
    }
    long valueCents = toCents(value);
    long changeCents = toCents(change);
    return new Figures(
        valueCents,
        rate == null ? null : toCents(value.multiply(rate)),
        changeCents,
        rate == null ? null : toCents(change.multiply(rate)),
        percent(valueCents, changeCents),
        partial);
  }

  private static BigDecimal cents(long cents) {
    return BigDecimal.valueOf(cents, 2);
  }

  // Arredonda uma vez por carteira, a partir do valor cheio. HALF_EVEN porque no meio centavo não
  // há lado certo e o arredondamento bancário não acumula viés ao somar carteiras (ADR-035).
  private static long toCents(BigDecimal amount) {
    BigDecimal rounded = amount.multiply(HUNDRED).setScale(0, RoundingMode.HALF_EVEN);
    if (rounded.abs().compareTo(BigDecimal.valueOf(MAX_SAFE_CENTS)) > 0) {
      return rounded.signum() < 0 ? -MAX_SAFE_CENTS : MAX_SAFE_CENTS;
    }
    return rounded.longValueExact();
  }

  // Variação sobre o valor de 24 h atrás (atual menos variação). Base zero ou negativa não tem
  // percentual com sentido: nulo. Razão, não dinheiro: texto com 2 casas, HALF_EVEN.
  static String percent(long valueCents, long changeCents) {
    long base = valueCents - changeCents;
    if (base <= 0) {
      return null;
    }
    return BigDecimal.valueOf(changeCents)
        .multiply(HUNDRED)
        .divide(BigDecimal.valueOf(base), 2, RoundingMode.HALF_EVEN)
        .toPlainString();
  }
}
