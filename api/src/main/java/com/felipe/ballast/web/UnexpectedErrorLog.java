package com.felipe.ballast.web;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.postgresql.util.PSQLException;
import org.slf4j.Logger;
import org.springframework.dao.DataIntegrityViolationException;

// O Postgres põe o valor da linha na mensagem só nas classes 22 (data exception: "invalid input
// syntax for type uuid: ...") e 23 (integrity constraint: "Key (...)=(...)", "Failing row
// contains"). Nesses casos o log leva classe, SQLState, constraint e os frames, sem mensagem
// nenhuma. Qualquer outro erro, inclusive deadlock e erro de SQL, loga completo.
public final class UnexpectedErrorLog {

  private static final String APP_PACKAGE = "com.felipe.ballast.";

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
        constraint,
        new FramesOnly(ex));
  }

  // O logback imprime a mensagem de cada causa no stack. Um Throwable próprio, sem mensagem e sem
  // causa, leva só os frames do app.
  private static final class FramesOnly extends Throwable {
    FramesOnly(Throwable origin) {
      super(null, null, false, true);
      Set<StackTraceElement> frames = new LinkedHashSet<>();
      for (Throwable cause = origin; cause != null; cause = cause.getCause()) {
        for (StackTraceElement frame : cause.getStackTrace()) {
          if (frame.getClassName().startsWith(APP_PACKAGE)) {
            frames.add(frame);
          }
        }
      }
      List<StackTraceElement> ordered = new ArrayList<>(frames);
      setStackTrace(ordered.toArray(new StackTraceElement[0]));
    }
  }

  private static boolean carriesRowData(Throwable ex) {
    boolean sawSqlState = false;
    boolean sawIntegrityViolation = false;
    for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException sql && sql.getSQLState() != null) {
        sawSqlState = true;
        if (sql.getSQLState().startsWith("22") || sql.getSQLState().startsWith("23")) {
          return true;
        }
      }
      if (cause instanceof DataIntegrityViolationException) {
        sawIntegrityViolation = true;
      }
    }
    // Sem SQLState para decidir, a violação de integridade fica sanitizada por cautela.
    return sawIntegrityViolation && !sawSqlState;
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }
}
