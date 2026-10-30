package com.felipe.ballast.crypto;

import java.math.BigDecimal;
import java.time.Instant;

// Reais por USDC como o provedor mandou, sem arredondar, e o instante da leitura (ADR-077).
public record UsdcQuote(BigDecimal priceBrl, Instant updatedAt) {}
