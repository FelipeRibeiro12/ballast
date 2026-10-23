package com.felipe.ballast.fixedincome;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

@Schema(name = "TitulosRendaFixa")
public record TitleListResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", format = "date")
        LocalDate data,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) IndicesResponse indices,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<TitleResponse> titulos) {}
