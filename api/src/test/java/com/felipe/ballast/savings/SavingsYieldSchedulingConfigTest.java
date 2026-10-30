package com.felipe.ballast.savings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

class SavingsYieldSchedulingConfigTest {

  // O serviço vem de fora: o teste só quer ver se o job e o agendador existem.
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(SavingsYieldSchedulingConfig.class, SavingsYieldJob.class)
          .withBean(SavingsYieldService.class, () -> mock(SavingsYieldService.class))
          .withPropertyValues("ballast.cofrinho.cron=0 30 3 * * *");

  @Test
  void theJobAndTheSchedulerExistWhenEnabled() {
    runner
        .withPropertyValues("ballast.cofrinho.job-enabled=true")
        .run(
            context -> {
              assertThat(context).hasSingleBean(ScheduledAnnotationBeanPostProcessor.class);
              assertThat(context).hasSingleBean(SavingsYieldJob.class);
            });
  }

  @Test
  void neitherExistsWhenDisabledOrMissing() {
    runner
        .withPropertyValues("ballast.cofrinho.job-enabled=false")
        .run(
            context -> {
              assertThat(context).doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class);
              assertThat(context).doesNotHaveBean(SavingsYieldJob.class);
            });
    runner.run(
        context -> assertThat(context).doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class));
  }
}
