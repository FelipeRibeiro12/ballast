package com.felipe.ballast.crypto;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// Liga o agendamento mesmo com os jobs de recorrência e de índices desligados.
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "ballast.cotacoes.job-enabled", havingValue = "true")
class QuoteSchedulingConfig {}
