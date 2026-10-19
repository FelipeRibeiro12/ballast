package com.felipe.ballast.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.felipe.ballast.TestcontainersConfiguration;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

// Servidor real (Tomcat): é o único jeito de exercitar Transfer-Encoding: chunked de verdade.
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "ballast.password.memory-kib=8",
      "ballast.password.iterations=1",
      "ballast.password.parallelism=1"
    })
class BodyLimitTest {

  private static final String TOO_LARGE = "{\"erro\":\"corpo grande demais\"}";
  private static final int LIMIT = 4096;

  @LocalServerPort int port;

  private final HttpClient client =
      HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

  private HttpRequest.Builder request(String path) {
    return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
        .header("Content-Type", "application/json");
  }

  // JSON válido com espaços no fim até o tamanho pedido.
  private static String loginBodyOfSize(int bytes) {
    String json = "{\"email\":\"ninguem@exemplo.test\",\"senha\":\"qualquer-senha-123\"}";
    return json + " ".repeat(bytes - json.length());
  }

  // Gera o corpo sob demanda e conta quanto o cliente puxou: se o servidor lesse tudo, o
  // contador chegaria ao total.
  private static final class Endless extends InputStream {
    final AtomicLong produced = new AtomicLong();
    private final long total;

    Endless(long total) {
      this.total = total;
    }

    @Override
    public int read() {
      if (produced.get() >= total) {
        return -1;
      }
      produced.incrementAndGet();
      return ' ';
    }

    @Override
    public int read(byte[] buffer, int off, int len) {
      if (produced.get() >= total) {
        return -1;
      }
      int n = (int) Math.min(len, total - produced.get());
      java.util.Arrays.fill(buffer, off, off + n, (byte) ' ');
      produced.addAndGet(n);
      return n;
    }
  }

  // A recusa vem do filtro, antes da autenticação: vale para qualquer rota, com ou sem sessão.
  @Test
  void rejectsDeclaredLengthAboveTheLimitOnAnyRouteWith413() throws Exception {
    for (String path : new String[] {"/contas", "/contas/" + java.util.UUID.randomUUID()}) {
      HttpResponse<String> response =
          client.send(
              request(path)
                  .method("POST", BodyPublishers.ofString(loginBodyOfSize(LIMIT + 1)))
                  .build(),
              BodyHandlers.ofString(StandardCharsets.UTF_8));

      assertThat(response.statusCode()).as(path).isEqualTo(413);
      assertThat(response.body()).as(path).isEqualTo(TOO_LARGE);
    }
  }

  @Test
  void aBodyExactlyAtTheLimitOnAnotherRouteReachesTheAuthentication() throws Exception {
    HttpResponse<String> response =
        client.send(
            request("/contas").POST(BodyPublishers.ofString(loginBodyOfSize(LIMIT))).build(),
            BodyHandlers.ofString(StandardCharsets.UTF_8));

    assertThat(response.statusCode()).isEqualTo(401);
  }

  @Test
  void rejectsDeclaredLengthAboveTheLimitWith413() throws Exception {
    HttpResponse<String> response =
        client.send(
            request("/auth/login")
                .POST(BodyPublishers.ofString(loginBodyOfSize(LIMIT + 1)))
                .build(),
            BodyHandlers.ofString(StandardCharsets.UTF_8));

    assertThat(response.statusCode()).isEqualTo(413);
    assertThat(response.body()).isEqualTo(TOO_LARGE);
  }

  @Test
  void rejectsChunkedBodyAboveTheLimitWithoutReadingItAll() throws Exception {
    long total = 200L * 1024 * 1024;
    Endless body = new Endless(total);

    try {
      HttpResponse<String> response =
          client.send(
              request("/auth/login").POST(BodyPublishers.ofInputStream(() -> body)).build(),
              BodyHandlers.ofString(StandardCharsets.UTF_8));
      assertThat(response.statusCode()).isEqualTo(413);
      assertThat(response.body()).isEqualTo(TOO_LARGE);
    } catch (IOException e) {
      // Com um upload de centenas de MB em andamento o Tomcat fecha a conexão depois de engolir
      // um pedaço, e o cliente vê o reset em vez da resposta. Nesse caso o que se prova é o
      // limite do que foi puxado; o 413 com corpo fica provado pelos outros testes.
    }

    assertThat(body.produced.get()).as("bytes puxados pelo cliente").isLessThan(total / 10);
  }

  @Test
  void acceptsABodyExactlyAtTheLimitWhetherSizedOrChunked() throws Exception {
    String exact = loginBodyOfSize(LIMIT);

    HttpResponse<String> sized =
        client.send(
            request("/auth/login").POST(BodyPublishers.ofString(exact)).build(),
            BodyHandlers.ofString(StandardCharsets.UTF_8));
    HttpResponse<String> chunked =
        client.send(
            request("/auth/login")
                .POST(
                    BodyPublishers.ofInputStream(
                        () ->
                            new java.io.ByteArrayInputStream(
                                exact.getBytes(StandardCharsets.UTF_8))))
                .build(),
            BodyHandlers.ofString(StandardCharsets.UTF_8));

    // Passou do limite sem estourar: chegou até a verificação de credenciais.
    assertThat(sized.statusCode()).isEqualTo(401);
    assertThat(chunked.statusCode()).isEqualTo(401);
  }

  @Test
  void rejectsChunkedBodyOneByteAboveTheLimit() throws Exception {
    String oversized = loginBodyOfSize(LIMIT + 1);

    HttpResponse<String> response =
        client.send(
            request("/auth/login")
                .POST(
                    BodyPublishers.ofInputStream(
                        () ->
                            new java.io.ByteArrayInputStream(
                                oversized.getBytes(StandardCharsets.UTF_8))))
                .build(),
            BodyHandlers.ofString(StandardCharsets.UTF_8));

    assertThat(response.statusCode()).isEqualTo(413);
    assertThat(response.body()).isEqualTo(TOO_LARGE);
  }
}
