package com.felipe.ballast.savings;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// O agendamento do cofrinho liga sozinho: os outros jobs têm a flag deles.
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "ballast.cofrinho.job-enabled", havingValue = "true")
class SavingsYieldSchedulingConfig {}
