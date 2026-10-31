package com.felipe.ballast.cards;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

@Schema(name = "Fatura")
public record InvoiceResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Data de vencimento")
        LocalDate vencimento,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Data de fechamento")
        LocalDate fechamento,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Despesas menos receitas do cartão com dataCaixa no vencimento",
            minimum = "-9007199254740991",
            maximum = "9007199254740991")
        long totalCentavos,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description =
                "Transferências para o cartão realizadas depois do fechamento da fatura e até o da seguinte",
            minimum = "-9007199254740991",
            maximum = "9007199254740991")
        long pagoCentavos,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "total menos pago; negativo quando pagou a mais",
            minimum = "-9007199254740991",
            maximum = "9007199254740991")
        long restanteCentavos,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            allowableValues = {"aberta", "fechada", "paga"})
        String situacao,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description =
                "true quando o dono fechou a fatura antes do dia de fechamento e ela ainda pode"
                    + " ser reaberta")
        boolean fechadaManualmente) {}
