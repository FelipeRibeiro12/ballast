package com.felipe.ballast.cards;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

@Schema(name = "Faturas")
public record InvoiceListResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID contaId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<InvoiceResponse> faturas) {}
