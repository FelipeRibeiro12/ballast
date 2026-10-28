package com.felipe.ballast.thirdparties;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "LigarTerceiroRequest")
record LinkThirdPartyRequest(
    @JsonProperty("terceiroId")
        @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            description = "Nulo solta o vínculo")
        String thirdPartyId) {}
