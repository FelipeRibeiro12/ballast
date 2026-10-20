package com.felipe.ballast.accounts;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.UUID;

@Schema(name = "Cartao")
public record CardResponse(
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Centavos de real, sempre positivo",
            minimum = "1",
            maximum = "9007199254740991")
        long limiteCentavos,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "31")
        int diaFechamento,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "31")
        int diaVencimento,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid") UUID contaPagamentoId,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description =
                "Limite mais o saldo da conta, nunca acima do limite. Negativo quando a dívida"
                    + " passa do limite",
            minimum = "-9007199254740990",
            maximum = "9007199254740991")
        long disponivelCentavos) {

  // O disponível para no limite: saldo positivo (fatura paga a mais) não aumenta o limite do
  // cartão, e limite mais saldo chegaria a 2 x 9007199254740991, fora do inteiro seguro do
  // JavaScript. A dívida acima do limite aparece como disponível negativo.
  static CardResponse from(AccountSummary.CardSummary card, BigDecimal balance) {
    BigDecimal available = card.limit().add(balance);
    if (available.compareTo(card.limit()) > 0) {
      available = card.limit();
    }
    return new CardResponse(
        toCents(card.limit()),
        card.closingDay(),
        card.dueDay(),
        card.paymentAccountId(),
        toCents(available));
  }

  private static long toCents(BigDecimal amount) {
    return amount.movePointRight(2).longValueExact();
  }
}
