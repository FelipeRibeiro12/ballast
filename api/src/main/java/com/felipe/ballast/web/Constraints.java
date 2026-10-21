package com.felipe.ballast.web;

import org.hibernate.exception.ConstraintViolationException;
import org.postgresql.util.PSQLException;

// Reconhece a violação de uma constraint pelo nome. O nome vem do Postgres no JdbcClient
// (PSQLException) e do Hibernate no JPA; o gatilho da V12 só preenche o primeiro, por isso os dois
// são olhados.
public final class Constraints {

  private static final int MAX_DEPTH = 16;

  private Constraints() {}

  public static boolean isViolated(Throwable error, String name) {
    Throwable cause = error;
    for (int depth = 0; cause != null && depth < MAX_DEPTH; depth++) {
      if (cause instanceof PSQLException psql
          && psql.getServerErrorMessage() != null
          && name.equals(psql.getServerErrorMessage().getConstraint())) {
        return true;
      }
      if (cause instanceof ConstraintViolationException violation
          && name.equals(violation.getConstraintName())) {
        return true;
      }
      cause = cause.getCause();
    }
    return false;
  }
}
