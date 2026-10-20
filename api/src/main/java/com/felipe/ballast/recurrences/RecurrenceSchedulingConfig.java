package com.felipe.ballast.recurrences;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// O scheduling só liga aqui, e fica desligado com ballast.recurrence.job-enabled=false. Os testes
// de integração desligam: sem isto o job rodaria sozinho no meio deles.
@Configuration
@EnableScheduling
@ConditionalOnProperty(
    name = "ballast.recurrence.job-enabled",
    havingValue = "true",
    matchIfMissing = true)
class RecurrenceSchedulingConfig {}
