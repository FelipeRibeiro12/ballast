package com.felipe.ballast.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

// Só existe com o perfil seed (`make seed`). Sem servidor web: roda, grava e a JVM sai.
// Erro de validação derruba a execução com código diferente de zero.
@Component
@Profile("seed")
class SeedRunner implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(SeedRunner.class);

  private final UserSeeder seeder;
  private final SeedProperties properties;

  SeedRunner(UserSeeder seeder, SeedProperties properties) {
    this.seeder = seeder;
    this.properties = properties;
  }

  @Override
  public void run(ApplicationArguments args) {
    UserSeeder.Result result =
        seeder.seed(properties.email(), properties.password(), properties.name());
    if (result.created()) {
      log.info("usuário criado (id {})", result.userId());
    } else {
      log.info("usuário já existe, nada feito");
    }
  }
}
