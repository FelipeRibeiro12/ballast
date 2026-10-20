package com.felipe.ballast.entries;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.felipe.ballast.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// Uma violação de CHECK que passou pela validação da borda chega ao handler de 500. O texto do
// Postgres traz a linha inteira ("Failing row contains"), então o log só pode ter classe,
// SQLState e nome da constraint.
@IntegrationTest
@Import(UnhandledConstraintViolationLogTest.BadInsert.class)
@ExtendWith(OutputCaptureExtension.class)
class UnhandledConstraintViolationLogTest {

  private static final String MARKER = "Marcador Zeta 4821";

  // Insere sem passar pelo serviço: o espaço na ponta do título quebra o CHECK de btrim.
  @TestConfiguration
  static class BadInsert {
    @RestController
    static class Controller {
      private final JdbcClient jdbc;

      Controller(JdbcClient jdbc) {
        this.jdbc = jdbc;
      }

      @GetMapping("/teste/lancamento-invalido")
      void insert(
          @RequestParam UUID usuario, @RequestParam UUID conta, @RequestParam UUID categoria) {
        jdbc.sql(
                "INSERT INTO lancamento (id, usuario_id, tipo, titulo, categoria_id, conta_id,"
                    + " valor, data_competencia, data_caixa) VALUES (:id, :u, 'despesa', :t, :cat,"
                    + " :conta, 10.00, DATE '2026-03-10', DATE '2026-03-10')")
            .param("id", UUID.randomUUID())
            .param("u", usuario)
            .param("t", " " + MARKER)
            .param("cat", categoria)
            .param("conta", conta)
            .update();
      }
    }
  }

  @Autowired MockMvc mvc;

  @Test
  void aConstraintViolationLogsOnlyClassSqlStateAndConstraintName(CapturedOutput output)
      throws Exception {
    EntriesApi.Actor actor = new EntriesApi(mvc).newActor();

    mvc.perform(
            get("/teste/lancamento-invalido")
                .cookie(actor.session())
                .param("usuario", actor.user().id().toString())
                .param("conta", actor.accountId())
                .param("categoria", actor.expenseCategoryId()))
        .andExpect(status().isInternalServerError())
        .andExpect(content().json("{\"erro\":\"erro interno\"}", true));

    assertThat(output.getAll())
        .contains("lancamento_titulo_sem_espaco_nas_pontas")
        .contains("23514")
        .contains("DataIntegrityViolationException")
        .doesNotContain(MARKER)
        .doesNotContain("Failing row");
  }
}
