package com.felipe.ballast.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// Sem contexto do Spring: o que importa aqui é só o que o advice devolve ao cliente.
class ApiExceptionHandlerTest {

  @RestController
  static class Boom {
    @GetMapping("/boom")
    String boom() {
      throw new IllegalStateException("segredo interno: jdbc:postgresql://host/banco");
    }

    // Como o JdbcClient entrega o deadlock: SQLException com o SQLState no fim da cadeia.
    @GetMapping("/deadlock-embrulhado")
    String wrappedDeadlock() {
      throw new IllegalStateException(
          new org.springframework.jdbc.UncategorizedSQLException(
              "x", "DELETE", new java.sql.SQLException("deadlock detected", "40P01")));
    }
  }

  private final MockMvc mvc =
      MockMvcBuilders.standaloneSetup(new Boom())
          .setControllerAdvice(new ApiExceptionHandler())
          .build();

  @Test
  void unexpectedErrorBecomesAGeneric500WithoutDetails() throws Exception {
    mvc.perform(get("/boom"))
        .andExpect(status().isInternalServerError())
        .andExpect(content().json("{\"erro\":\"erro interno\"}", true));
  }

  @Test
  void aDeadlockBecomesA503TheClientCanRetry() throws Exception {
    mvc.perform(get("/deadlock-embrulhado"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(content().json("{\"erro\":\"serviço indisponível\"}", true));
  }

  @Test
  void unknownRouteBecomes404InJson() throws Exception {
    mvc.perform(get("/nao-existe"))
        .andExpect(status().isNotFound())
        .andExpect(content().json("{\"erro\":\"não encontrado\"}", true));
  }

  @Test
  void wrongMethodKeepsTheAllowHeader() throws Exception {
    mvc.perform(post("/boom"))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(header().string("Allow", "GET"))
        .andExpect(content().json("{\"erro\":\"método não permitido\"}", true));
  }
}
