package com.felipe.ballast.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.felipe.ballast.TestcontainersConfiguration;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

// Contexto e banco próprios: o teto conta todos os usuários da tabela.
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    properties = {
      "ballast.registration-open=true",
      "ballast.registration-max-users=2",
      "ballast.recurrence.job-enabled=false",
      "ballast.password.memory-kib=8",
      "ballast.password.iterations=1",
      "ballast.password.parallelism=1"
    })
@AutoConfigureMockMvc
class RegistrationCapTest {

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired IdentityService identity;

  @BeforeEach
  void emptyUsers() {
    jdbc.sql("DELETE FROM usuario").update();
  }

  private MockHttpServletResponse register(String email) throws Exception {
    return mvc.perform(
            post("/auth/cadastro")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"senha\":\"" + TestUsers.PASSWORD + "\"}"))
        .andReturn()
        .getResponse();
  }

  private long users() {
    return jdbc.sql("SELECT count(*) FROM usuario").query(Long.class).single();
  }

  @Test
  void refusesRegistrationOnceTheCapIsReached() throws Exception {
    assertThat(register(TestUsers.uniqueEmail()).getStatus()).isEqualTo(201);
    assertThat(register(TestUsers.uniqueEmail()).getStatus()).isEqualTo(201);

    MockHttpServletResponse refused = register(TestUsers.uniqueEmail());

    assertThat(refused.getStatus()).isEqualTo(403);
    assertThat(refused.getContentAsString(StandardCharsets.UTF_8))
        .isEqualTo("{\"erro\":\"cadastro fechado: limite de usuários atingido\"}");
    assertThat(users()).isEqualTo(2);
  }

  // Sem a trava, os pedidos que contam 1 ao mesmo tempo passariam todos pela última vaga.
  @Test
  void concurrentRegistrationsTakeTheLastSlotOnlyOnce() throws Exception {
    identity.register(TestUsers.uniqueEmail(), TestUsers.PASSWORD, null);
    int threads = 8;
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      List<Future<Boolean>> results = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        results.add(
            pool.submit(
                () -> {
                  start.await();
                  try {
                    identity.register(TestUsers.uniqueEmail(), TestUsers.PASSWORD, null);
                    return true;
                  } catch (RegistrationFullException e) {
                    return false;
                  }
                }));
      }
      start.countDown();
      int created = 0;
      for (Future<Boolean> result : results) {
        created += result.get() ? 1 : 0;
      }

      assertThat(created).isEqualTo(1);
      assertThat(users()).isEqualTo(2);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void seedRespectsTheCapToo() {
    identity.register(TestUsers.uniqueEmail(), TestUsers.PASSWORD, null);
    identity.register(TestUsers.uniqueEmail(), TestUsers.PASSWORD, null);

    assertThatThrownBy(() -> identity.register(TestUsers.uniqueEmail(), TestUsers.PASSWORD, null))
        .isInstanceOf(RegistrationFullException.class)
        .hasMessage("limite de usuários atingido");
  }
}
