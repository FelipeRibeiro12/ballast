package com.felipe.ballast.fixedincome;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// O agendamento do job de recorrência só liga com ele; o de índices precisa ligar sozinho.
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "ballast.indices.job-enabled", havingValue = "true")
class IndexSchedulingConfig {}
