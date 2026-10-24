package com.felipe.ballast.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.identity.TestUsers;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

// Chave repetida no JSON: o Jackson fica com a última e dois validadores no caminho (por exemplo
// um proxy e a API) podem enxergar valores diferentes. Aqui vira erro.
@IntegrationTest
class DuplicateKeyTest {

  @Autowired MockMvc mvc;

  @Test
  void repeatedKeyInLoginIsBadRequest() throws Exception {
    MockHttpServletResponse response =
        mvc.perform(
                post("/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"email\":\"a@exemplo.test\",\"email\":\"b@exemplo.test\","
                            + "\"senha\":\"qualquer-senha-123\"}"))
            .andReturn()
            .getResponse();

    assertThat(response.getStatus()).isEqualTo(400);
  }

  @Test
  void repeatedKeyInAnAuthenticatedBodyIsBadRequest() throws Exception {
    TestUsers users = new TestUsers(mvc);
    Cookie session = users.session(users.register());

    MockHttpServletResponse response =
        mvc.perform(
                post("/contas")
                    .cookie(session)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"nome\":\"A\",\"nome\":\"B\",\"tipo\":\"corrente\"}"))
            .andReturn()
            .getResponse();

    assertThat(response.getStatus()).isEqualTo(400);
  }
}
