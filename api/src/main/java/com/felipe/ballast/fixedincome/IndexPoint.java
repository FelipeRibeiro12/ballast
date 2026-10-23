package com.felipe.ballast.fixedincome;

import java.math.BigDecimal;
import java.time.LocalDate;

// Um valor publicado de uma série: o percentual, como o Banco Central publica.
public record IndexPoint(LocalDate date, BigDecimal value) {}
