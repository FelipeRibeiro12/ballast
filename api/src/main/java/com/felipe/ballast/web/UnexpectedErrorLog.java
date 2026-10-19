package com.felipe.ballast.web;

import java.sql.SQLException;
import org.postgresql.util.PSQLException;
import org.slf4j.Logger;
import org.springframework.dao.DataIntegrityViolationException;

// A mensagem do Postgres numa violação de constraint traz a linha inteira ("Key (email)=(...)",
// "Failing row contains (...)"), com dado pessoal e financeiro. Nesses casos o log leva só a
// classe, o SQLState e o nome da constraint; o stack imprimiria a mensagem de novo nas causas.
public final class UnexpectedErrorLog {

  private UnexpectedErrorLog() {}

  public static void error(Logger log, String message, Throwable ex) {
    if (!carriesRowData(ex)) {
      log.error(message, ex);
      return;
    }
    String sqlState = "";
    String constraint = "";
    for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
      if (cause instanceof PSQLException psql) {
        sqlState = nullToEmpty(psql.getSQLState());
        if (psql.getServerErrorMessage() != null) {
          constraint = nullToEmpty(psql.getServerErrorMessage().getConstraint());
        }
        break;
      }
      if (cause instanceof SQLException sql && sqlState.isEmpty()) {
        sqlState = nullToEmpty(sql.getSQLState());
      }
    }
    log.error(
        "{} tipo={} sqlstate={} constraint={}",
        message,
        ex.getClass().getName(),
        sqlState,
        constraint);
  }

  private static boolean carriesRowData(Throwable ex) {
    for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
      if (cause instanceof DataIntegrityViolationException || cause instanceof PSQLException) {
        return true;
      }
    }
    return false;
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }
}
