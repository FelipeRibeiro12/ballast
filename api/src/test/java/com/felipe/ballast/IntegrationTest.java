package com.felipe.ballast;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

// Mesmo conjunto de propriedades em todos os testes de integração: o Spring reaproveita o
// contexto (e o container do Postgres) entre as classes. Argon2 barato, cadastro aberto,
// api-docs ligado, que é como a spec é gerada, e o job de recorrência desligado.
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Import({TestcontainersConfiguration.class, TestClock.Config.class})
@SpringBootTest(
    properties = {
      "ballast.registration-open=true",
      "springdoc.api-docs.enabled=true",
      "ballast.recurrence.job-enabled=false",
      "ballast.password.memory-kib=8",
      "ballast.password.iterations=1",
      "ballast.password.parallelism=1"
    })
@AutoConfigureMockMvc
public @interface IntegrationTest {}
