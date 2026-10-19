package com.felipe.ballast;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

  public static final String APP_USER = "ballast_app";
  public static final String APP_PASSWORD = "ballast_app_test";

  // Usado também pelos testes que sobem container próprio.
  public static PostgreSQLContainer newPostgres() {
    return new PostgreSQLContainer(DockerImageName.parse("postgres:17"))
        .withInitScript("db/init-app-role.sql");
  }

  public static void registerProperties(
      DynamicPropertyRegistry registry, PostgreSQLContainer postgres) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", () -> APP_USER);
    registry.add("spring.datasource.password", () -> APP_PASSWORD);
    registry.add("spring.flyway.user", postgres::getUsername);
    registry.add("spring.flyway.password", postgres::getPassword);
    registry.add("spring.flyway.placeholders.app_user", () -> APP_USER);
  }

  @Bean
  PostgreSQLContainer postgresContainer() {
    return newPostgres();
  }

  // Sem @ServiceConnection: ele ligaria a aplicação ao superusuário do container e a
  // separação do ADR-021 não seria exercitada. Flyway = dono; datasource = papel só DML.
  @Bean
  DynamicPropertyRegistrar databaseProperties(PostgreSQLContainer postgres) {
    return registry -> registerProperties(registry, postgres);
  }
}
