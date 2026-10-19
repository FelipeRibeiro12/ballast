package com.felipe.ballast.health;

import io.swagger.v3.oas.annotations.media.Schema;

public record HealthError(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String erro) {}
