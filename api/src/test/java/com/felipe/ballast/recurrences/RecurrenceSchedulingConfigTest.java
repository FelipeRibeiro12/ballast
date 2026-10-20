package com.felipe.ballast.recurrences;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

class RecurrenceSchedulingConfigTest {

  @Configuration
  static class WithAScheduledBean {
    @Bean
    Object ticker() {
      return new Object() {
        @Scheduled(cron = "0 0 3 * * *", zone = "America/Sao_Paulo")
        void tick() {}
      };
    }
  }

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(RecurrenceSchedulingConfig.class, WithAScheduledBean.class);

  @Test
  void schedulingIsOnWhenEnabledOrWhenThePropertyIsMissing() {
    runner
        .withPropertyValues("ballast.recurrence.job-enabled=true")
        .run(
            context ->
                assertThat(context).hasSingleBean(ScheduledAnnotationBeanPostProcessor.class));
    runner.run(
        context -> assertThat(context).hasSingleBean(ScheduledAnnotationBeanPostProcessor.class));
  }

  @Test
  void schedulingStaysOffWhenThePropertyIsFalse() {
    runner
        .withPropertyValues("ballast.recurrence.job-enabled=false")
        .run(
            context ->
                assertThat(context).doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class));
  }
}
