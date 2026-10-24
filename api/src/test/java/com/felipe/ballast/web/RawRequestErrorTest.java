package com.felipe.ballast.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.felipe.ballast.TestcontainersConfiguration;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

// Caminho que o Tomcat recusa antes de chegar ao Spring (barra codificada, chave): o socket cru
// é o único jeito de mandar, porque o HttpClient e o MockMvc normalizam o caminho.
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RawRequestErrorTest {

  @LocalServerPort int port;

  private String send(String target) throws IOException {
    try (Socket socket = new Socket("127.0.0.1", port)) {
      socket.setSoTimeout(5000);
      socket
          .getOutputStream()
          .write(
              ("GET " + target + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                  .getBytes(StandardCharsets.US_ASCII));
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      socket.getInputStream().transferTo(out);
      return out.toString(StandardCharsets.UTF_8);
    }
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"/contas/%2F", "/contas/{}", "/contas/%2f..%2f", "/contas/a|b"})
  void tomcatRejectionAnswersJsonNotHtml(String target) throws Exception {
    String response = send(target);

    assertThat(response).startsWith("HTTP/1.1 400");
    assertThat(response.toLowerCase()).contains("content-type: application/json");
    assertThat(response).contains("{\"erro\":\"requisição inválida\"}").doesNotContain("<html");
    // Sem versão, caminho nem classe do Tomcat no corpo.
    assertThat(response).doesNotContain("Tomcat").doesNotContain("Apache");
  }

  @Test
  void tomcatRejectionAlsoCarriesNosniff() throws Exception {
    assertThat(send("/contas/%2F").toLowerCase()).contains("x-content-type-options: nosniff");
  }
}
