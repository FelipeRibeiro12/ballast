package com.felipe.ballast.crypto;

import java.math.BigDecimal;
import java.time.Instant;

// Preço em dólar como o provedor mandou, sem arredondar, e o instante em que ele atualizou.
public record ProviderQuote(BigDecimal priceUsd, Instant updatedAt) {}
