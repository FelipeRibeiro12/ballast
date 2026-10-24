package com.felipe.ballast.fixedincome;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

// Servidor de verdade na porta local: o servidor simulado do Spring não sabe ser lento nem gigante.
class BcbIndexSourceLimitsTest {

  private static final LocalDate FROM = LocalDate.of(2026, 10, 1);
  private static final LocalDate TO = LocalDate.of(2026, 10, 5);

  private HttpServer server;
  private final CountDownLatch finished = new CountDownLatch(1);

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
  }

  @AfterEach
  void tearDown() {
    finished.countDown();
    server.stop(0);
  }

  private BcbIndexSource source(Duration deadline, int maxBytes) {
    server.start();
    RestClient client =
        RestClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build();
    return new BcbIndexSource(client, deadline, maxBytes);
  }

  @Test
  void aBodyOverTheByteCapIsRefusedWithoutReadingItAll() {
    byte[] giant = new byte[3 * 1024 * 1024];
    Arrays.fill(giant, (byte) ' ');
    server.createContext(
        "/",
        exchange -> {
          exchange.sendResponseHeaders(200, giant.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(giant);
          } catch (IOException e) {
            // O cliente larga a conexão ao passar do teto; é o esperado.
          }
        });
    BcbIndexSource source = source(Duration.ofSeconds(10), 2 * 1024 * 1024);

    assertThatThrownBy(() -> source.fetch(IndexSeries.CDI, FROM, TO))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("teto");
  }

  @Test
  void aBodyUnderTheCapIsRead() {
    server.createContext(
        "/",
        exchange -> {
          byte[] body =
              "[{\"data\":\"01/10/2026\",\"valor\":\"0.05\"}]".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    BcbIndexSource source = source(Duration.ofSeconds(10), 2 * 1024 * 1024);

    List<IndexPoint> points = source.fetch(IndexSeries.CDI, FROM, TO);

    assertThat(points).hasSize(1);
  }

  @Test
  void aServerThatNeverAnswersStopsAtTheTotalDeadline() {
    server.createContext(
        "/",
        exchange -> {
          try {
            finished.await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
    BcbIndexSource source = source(Duration.ofMillis(800), 2 * 1024 * 1024);

    assertTimeoutPreemptively(
        Duration.ofSeconds(4),
        () ->
            assertThatThrownBy(() -> source.fetch(IndexSeries.CDI, FROM, TO))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prazo"));
  }

  @Test
  void aServerThatDripsOneByteAtATimeStopsAtTheTotalDeadline() {
    server.createContext(
        "/",
        exchange -> {
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write('[');
            out.flush();
            while (finished.getCount() > 0) {
              out.write(' ');
              out.flush();
              Thread.sleep(50);
            }
          } catch (IOException | InterruptedException e) {
            // O cliente desistiu no prazo.
          }
        });
    BcbIndexSource source = source(Duration.ofMillis(800), 2 * 1024 * 1024);

    assertTimeoutPreemptively(
        Duration.ofSeconds(4),
        () ->
            assertThatThrownBy(() -> source.fetch(IndexSeries.CDI, FROM, TO))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prazo"));
  }
}
