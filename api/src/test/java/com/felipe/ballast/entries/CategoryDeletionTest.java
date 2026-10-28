package com.felipe.ballast.entries;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;

import com.felipe.ballast.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class CategoryDeletionTest {

  @Autowired MockMvc mvc;

  @Test
  void deletingACategoryMovesItsEntriesToOutros() throws Exception {
    EntriesApi api = new EntriesApi(mvc);
    EntriesApi.Actor actor = api.newActor();
    JsonNode entry = api.createOk(actor.session(), EntriesApi.expenseBody(actor));

    var response =
        mvc.perform(delete("/categorias/" + actor.expenseCategoryId()).cookie(actor.session()))
            .andReturn()
            .getResponse();

    assertThat(response.getStatus()).isEqualTo(204);
    JsonNode moved =
        EntriesApi.body(api.find(actor.session(), entry.get("id").asString()));
    assertThat(moved.get("categoriaId").asString())
        .isEqualTo(api.categoryId(actor.session(), "Outros"));
  }
}
