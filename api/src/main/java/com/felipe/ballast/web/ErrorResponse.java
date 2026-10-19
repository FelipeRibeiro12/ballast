package com.felipe.ballast.web;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "Erro")
public record ErrorResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String erro) {}
