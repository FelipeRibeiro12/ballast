package com.felipe.ballast.identity;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

// Teto de usuários para caber no tier gratuito (ADR-049). Para tirar: apague esta classe, a
// RegistrationFullException, a chamada em IdentityService.register, o catch no
// RegistrationController e a chave registration-max-users do application.yml.
@Component
class RegistrationCap {

  static final int DEFAULT_MAX_USERS = 20;
  private static final int LIMIT = 1_000_000;
  private static final String MESSAGE =
      "CADASTRO_MAX_USUARIOS deve ser um inteiro entre 1 e " + LIMIT;

  // Chave fixa da trava consultiva: só o cadastro usa.
  private static final long LOCK_KEY = 0x62616c6c61737431L;

  private final int maxUsers;
  private final JdbcClient jdbc;

  RegistrationCap(@Value("${ballast.registration-max-users:}") String raw, JdbcClient jdbc) {
    this.maxUsers = parse(raw);
    this.jdbc = jdbc;
  }

  static int parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return DEFAULT_MAX_USERS;
    }
    int value;
    try {
      value = Integer.parseInt(raw.strip());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(MESSAGE);
    }
    if (value < 1 || value > LIMIT) {
      throw new IllegalArgumentException(MESSAGE);
    }
    return value;
  }

  // Roda dentro da transação do cadastro. A trava vale até o commit e enfileira os cadastros
  // simultâneos, para dois não contarem a mesma última vaga.
  void checkRoom() {
    jdbc.sql("SELECT 1 FROM pg_advisory_xact_lock(:key)")
        .param("key", LOCK_KEY)
        .query()
        .singleRow();
    long users = jdbc.sql("SELECT count(*) FROM usuario").query(Long.class).single();
    if (users >= maxUsers) {
      throw new RegistrationFullException();
    }
  }
}
