package com.felipe.ballast.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;

class UnexpectedErrorLogTest {

  private static final String VALUE = "valor-secreto-4821";

  private final Logger logger = (Logger) LoggerFactory.getLogger(UnexpectedErrorLogTest.class);
  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

  @BeforeEach
  void attachAppender() {
    appender.start();
    logger.addAppender(appender);
  }

  @AfterEach
  void detachAppender() {
    logger.detachAppender(appender);
  }

  // O que o logback imprime: a linha da mensagem mais o stack com a mensagem de cada causa.
  private String logged() {
    ILoggingEvent event = appender.list.get(0);
    String stack =
        event.getThrowableProxy() == null
            ? ""
            : ThrowableProxyUtil.asString(event.getThrowableProxy());
    return event.getFormattedMessage() + "\n" + stack;
  }

  private static PSQLException psql(String sqlState, String message, String constraint) {
    String fields = "SERROR\0C" + sqlState + "\0M" + message + "\0";
    if (constraint != null) {
      fields += "n" + constraint + "\0";
    }
    return new PSQLException(new ServerErrorMessage(fields));
  }

  @Test
  void dataExceptionClass22KeepsStackWithoutAnyMessage() {
    PSQLException cause =
        psql("22P02", "invalid input syntax for type uuid: \"" + VALUE + "\"", null);
    Exception ex = new DataIntegrityViolationException("wrapper com " + VALUE, cause);

    UnexpectedErrorLog.error(logger, "erro não tratado", ex);

    assertThat(appender.list).hasSize(1);
    assertThat(logged())
        .contains("22P02")
        .contains("com.felipe.ballast.web.UnexpectedErrorLogTest")
        .doesNotContain(VALUE);
  }

  @Test
  void integrityViolationClass23KeepsStackWithoutAnyMessage() {
    PSQLException cause =
        psql(
            "23505",
            "duplicate key. Key (email)=(" + VALUE + ") already exists.",
            "usuario_email_key");
    Exception ex = new DataIntegrityViolationException("wrapper com " + VALUE, cause);

    UnexpectedErrorLog.error(logger, "erro não tratado", ex);

    assertThat(logged())
        .contains("23505")
        .contains("usuario_email_key")
        .contains("com.felipe.ballast.web.UnexpectedErrorLogTest")
        .doesNotContain(VALUE);
  }

  @Test
  void deadlockLogsFullMessageAndStack() {
    PSQLException ex = psql("40P01", "deadlock detected " + VALUE, null);

    UnexpectedErrorLog.error(logger, "erro não tratado", ex);

    assertThat(appender.list.get(0).getThrowableProxy()).isNotNull();
    assertThat(logged())
        .contains("deadlock detected " + VALUE)
        .contains("com.felipe.ballast.web.UnexpectedErrorLogTest");
  }

  @Test
  void nonSqlExceptionLogsFully() {
    UnexpectedErrorLog.error(logger, "erro não tratado", new IllegalStateException(VALUE));

    assertThat(logged()).contains("IllegalStateException: " + VALUE);
  }

  @Test
  void sanitizedStackKeepsFramesOfEveryCause() {
    PSQLException root = psql("23514", "Failing row contains (" + VALUE + ")", "ck");
    Exception ex = new DataIntegrityViolationException("x", root);

    UnexpectedErrorLog.error(logger, "erro", ex);

    List<String> lines = logged().lines().filter(l -> l.contains("com.felipe.ballast")).toList();
    assertThat(lines).isNotEmpty();
  }
}
