package com.felipe.ballast.wallets;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// Liga o agendamento mesmo com os outros jobs desligados.
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "ballast.carteiras.job-enabled", havingValue = "true")
class WalletSchedulingConfig {}
