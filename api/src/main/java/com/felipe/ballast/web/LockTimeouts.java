package com.felipe.ballast.web;

import java.sql.SQLException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.QueryTimeoutException;

// Reconhece o estouro do lock_timeout. Pelo JdbcClient o Spring não traduz o 55P03 e a exceção
// chega como UncategorizedSQLException, então o SQLState na cadeia de causas é o sinal que vale;
// pelo JPA o Hibernate traduz para CannotAcquireLockException.
public final class LockTimeouts {

  private static final String LOCK_NOT_AVAILABLE = "55P03";
  private static final String DEADLOCK_DETECTED = "40P01";
  private static final String QUERY_CANCELED = "57014";
  private static final int MAX_DEPTH = 16;

  private LockTimeouts() {}

  public static boolean isLockTimeout(Throwable error) {
    Throwable cause = error;
    for (int depth = 0; cause != null && depth < MAX_DEPTH; depth++) {
      if (cause instanceof CannotAcquireLockException
          || (cause instanceof SQLException sql && LOCK_NOT_AVAILABLE.equals(sql.getSQLState()))) {
        return true;
      }
      cause = cause.getCause();
    }
    return false;
  }

  // 57014 também sai de cancelamento por quem administra o banco; para o cliente tentar de novo
  // vale igual. Pelo JPA o Hibernate traduz para QueryTimeoutException, que a cadeia também cobre.
  public static boolean isStatementTimeout(Throwable error) {
    Throwable cause = error;
    for (int depth = 0; cause != null && depth < MAX_DEPTH; depth++) {
      if (cause instanceof QueryTimeoutException
          || (cause instanceof SQLException sql && QUERY_CANCELED.equals(sql.getSQLState()))) {
        return true;
      }
      cause = cause.getCause();
    }
    return false;
  }

  // O Postgres desfaz uma das transações do ciclo; a outra segue. Para quem perdeu, tentar de novo
  // resolve, como no lock_timeout. Pelo JPA chega como PessimisticLockingFailureException.
  public static boolean isDeadlock(Throwable error) {
    Throwable cause = error;
    for (int depth = 0; cause != null && depth < MAX_DEPTH; depth++) {
      if (cause instanceof SQLException sql && DEADLOCK_DETECTED.equals(sql.getSQLState())) {
        return true;
      }
      cause = cause.getCause();
    }
    return false;
  }
}
