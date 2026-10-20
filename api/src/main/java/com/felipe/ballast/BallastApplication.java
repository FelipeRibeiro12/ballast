package com.felipe.ballast;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

// Sem UserDetailsServiceAutoConfiguration: ela cria um usuário em memória com senha gerada e a
// imprime no log. Aqui a autenticação é pela sessão (ADR-020) e nenhum usuário do framework existe.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
public class BallastApplication {

  public static void main(String[] args) {
    SpringApplication.run(BallastApplication.class, args);
  }
}
