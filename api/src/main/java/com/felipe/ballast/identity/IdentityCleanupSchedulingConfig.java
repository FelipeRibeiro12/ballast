package com.felipe.ballast.identity;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// Mesmo arranjo do job de recorrência: os testes desligam com
// ballast.identity-cleanup.job-enabled=false.
@Configuration
@EnableScheduling
@ConditionalOnProperty(
    name = "ballast.identity-cleanup.job-enabled",
    havingValue = "true",
    matchIfMissing = true)
class IdentityCleanupSchedulingConfig {}
