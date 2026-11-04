package com.felipe.ballast.crypto;

import java.math.BigDecimal;

// Corte único da poeira (ADR-082): valor absoluto abaixo de US$ 0,50 não aparece. Só a leitura
// usa, a gravação guarda tudo. Constante e não configuração, para a tela e o patrimônio nunca
// divergirem.
public final class DustCutoff {

  public static final long CENTS = 50;

  private static final BigDecimal LIMIT = new BigDecimal("0.50");

  private DustCutoff() {}

  // compareTo e não equals: 0.5000 vale como 0.50. Compara o valor cheio, sem arredondar antes,
  // senão 0.499 viraria 0.50 e escaparia do corte.
  public static boolean isDust(BigDecimal usd) {
    return usd.abs().compareTo(LIMIT) < 0;
  }

  public static boolean isDustCents(long usdCents) {
    return Math.abs(usdCents) < CENTS;
  }
}
