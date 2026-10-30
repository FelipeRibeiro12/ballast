package com.felipe.ballast.wallets;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.felipe.ballast.IntegrationTest;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

// A chave da Alchemy faz parte da URL. Aqui a conversa é HTTP de verdade contra um servidor local
// (nada de rede externa), porque o log do RestClient só aparece no caminho real.
@IntegrationTest
class WalletKeyLeakTest {

  private static final String KEY = "chave-secreta-de-teste-9f3a";
  private static final String ADDRESS = "0x5aaeb6053f3e94c9b9a09f33669435e7ef1beaed";

  @Autowired Environment environment;

  @Test
  void theHttpLoggersThatPrintTheUrlStayAtWarnOrAbove() {
    for (String name :
        List.of("org.springframework.web.client", "org.springframework.http.client")) {
      assertThat(environment.getProperty("logging.level." + name)).as(name).isEqualTo("WARN");
      ch.qos.logback.classic.Logger logger =
          (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(name);
      assertThat(logger.getEffectiveLevel().isGreaterOrEqual(Level.WARN)).as(name).isTrue();
    }
  }

  @Test
  void theKeyReachesTheServerInThePathAndNowhereElse() throws Exception {
    List<String> paths = new CopyOnWriteArrayList<>();
    HttpServer server = serve(200, paths);
    ListAppender<ILoggingEvent> logs = captureRoot();
    try {
      AlchemyWalletIndexer indexer = indexerFor(server);

      indexer.read(WalletNetwork.ETHEREUM, ADDRESS);

      assertThat(paths).isNotEmpty().allMatch(path -> path.equals("/v2/" + KEY));
      assertNoKeyInLogs(logs);
    } finally {
      release(logs);
      server.stop(0);
    }
  }

  @Test
  void aRefusedKeyKeepsTheKeyOutOfTheExceptionAndTheLog() throws Exception {
    HttpServer server = serve(401, new ArrayList<>());
    ListAppender<ILoggingEvent> logs = captureRoot();
    try {
      AlchemyWalletIndexer indexer = indexerFor(server);

      IndexerUnavailableException failure = null;
      try {
        indexer.read(WalletNetwork.ETHEREUM, ADDRESS);
      } catch (IndexerUnavailableException e) {
        failure = e;
      }

      assertThat(failure).isNotNull();
      assertThat(failure.reason()).isEqualTo(IndexerUnavailableException.Reason.REJECTED);
      assertThat(failure.getMessage()).doesNotContain(KEY);
      assertThat(failure.getCause()).isNull();
      assertNoKeyInLogs(logs);
    } finally {
      release(logs);
      server.stop(0);
    }
  }

  @Test
  void aServerThatIsDownKeepsTheKeyOutOfTheExceptionAndTheLog() throws Exception {
    HttpServer server = serve(200, new ArrayList<>());
    AlchemyWalletIndexer indexer = indexerFor(server);
    server.stop(0);
    ListAppender<ILoggingEvent> logs = captureRoot();
    try {
      IndexerUnavailableException failure = null;
      try {
        indexer.read(WalletNetwork.ETHEREUM, ADDRESS);
      } catch (IndexerUnavailableException e) {
        failure = e;
      }

      assertThat(failure).isNotNull();
      assertThat(failure.getMessage()).doesNotContain(KEY);
      assertThat(failure.getCause()).isNull();
      assertNoKeyInLogs(logs);
    } finally {
      release(logs);
    }
  }

  @Test
  void thePropertiesNeverPrintTheKeyOrAnExplicitUrl() {
    WalletsProperties properties =
        new WalletsProperties(
            KEY, "https://explicita.test/" + KEY, null, null, true, true, null, null);

    assertThat(properties.toString()).doesNotContain(KEY);
    assertThat(String.valueOf(properties)).doesNotContain("explicita");
  }

  private static AlchemyWalletIndexer indexerFor(HttpServer server) {
    String url = "http://127.0.0.1:" + server.getAddress().getPort();
    return new AlchemyWalletIndexer(
        new WalletsProperties(KEY, url, url, url, false, false, null, null));
  }

  // Responde JSON-RPC mínimo: saldo zero e nenhum token.
  private static HttpServer serve(int status, List<String> paths) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          paths.add(exchange.getRequestURI().getRawPath());
          String request =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          String body =
              request.contains("eth_getBalance")
                  ? "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"0x0\"}"
                  : "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tokenBalances\":[]}}";
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    return server;
  }

  private static ListAppender<ILoggingEvent> captureRoot() {
    ListAppender<ILoggingEvent> logs = new ListAppender<>();
    logs.start();
    ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME))
        .addAppender(logs);
    return logs;
  }

  private static void release(ListAppender<ILoggingEvent> logs) {
    ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME))
        .detachAppender(logs);
    logs.stop();
  }

  private static void assertNoKeyInLogs(ListAppender<ILoggingEvent> logs) {
    for (ILoggingEvent event : logs.list) {
      assertThat(event.getFormattedMessage()).doesNotContain(KEY).doesNotContain(ADDRESS);
      assertThat(event.getThrowableProxy()).isNull();
    }
  }
}
