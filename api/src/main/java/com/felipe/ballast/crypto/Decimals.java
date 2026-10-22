package com.felipe.ballast.crypto;

import java.math.BigDecimal;

// Quantidade e preço trafegam como texto (ADR-005, ADR-035): Number não guarda 18 casas.
final class Decimals {

  // O lookahead exige um dígito diferente de zero: "0" e "0.000" ficam de fora pelo próprio
  // formato. Sem sinal, sem expoente, sem ponto solto nas pontas.
  static final String QUANTITY_PATTERN = "(?=.*[1-9])[0-9]{1,20}(\\.[0-9]{1,18})?";
  static final String PRICE_PATTERN = "(?=.*[1-9])[0-9]{1,12}(\\.[0-9]{1,8})?";

  private Decimals() {}

  // Sem zero à direita e nunca em notação científica: 100 sai "100", não "1E+2".
  static String format(BigDecimal value) {
    return value.stripTrailingZeros().toPlainString();
  }

  // Casas que importam: "0.10" tem uma. Inteiro dá zero ou negativo.
  static int significantScale(BigDecimal value) {
    return value.stripTrailingZeros().scale();
  }
}
