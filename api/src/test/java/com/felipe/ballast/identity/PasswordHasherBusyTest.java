package com.felipe.ballast.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.felipe.ballast.TestcontainersConfiguration;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

// Contexto próprio com o argon2 sem vaga nenhuma, o estado de um flood de login em curso.
@Import({TestcontainersConfiguration.class, PasswordHasherBusyTest.BusyHasher.class})
@SpringBootTest(
    properties = {
      "ballast.password.memory-kib=8",
      "ballast.password.iterations=1",
      "ballast.password.parallelism=1"
    })
@AutoConfigureMockMvc
class PasswordHasherBusyTest {

  @Autowired MockMvc mvc;

  @Test
  void loginWithoutAFreeSlotAnswers503WithRetryAfter() throws Exception {
    MockHttpServletResponse response =
        mvc.perform(
                post("/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"email\":\"" + TestUsers.uniqueEmail() + "\",\"senha\":\"qualquer\"}"))
            .andReturn()
            .getResponse();

    assertThat(response.getStatus()).isEqualTo(503);
    assertThat(response.getHeader("Retry-After")).isEqualTo("5");
    assertThat(response.getContentAsString(StandardCharsets.UTF_8))
        .isEqualTo("{\"erro\":\"serviço indisponível\"}");
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class BusyHasher {

    @Bean
    @Primary
    PasswordHasher busyHasher(IdentityProperties properties) {
      return new PasswordHasher(properties, new Semaphore(0), Duration.ofMillis(50));
    }
  }
}
