package com.felipe.ballast.fixedincome;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

// Até quando há índice guardado. Nulo quando o Banco Central nunca respondeu.
@Schema(name = "Indices")
public record IndicesResponse(
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            type = "string",
            format = "date",
            description = "Último dia com CDI publicado guardado")
        LocalDate cdiAte,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            type = "string",
            format = "date",
            description = "Mês do último IPCA guardado, no primeiro dia")
        LocalDate ipcaAte) {}
