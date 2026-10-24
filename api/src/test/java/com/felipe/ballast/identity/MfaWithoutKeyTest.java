package com.felipe.ballast.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.felipe.ballast.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

// A API sobe sem MFA_CHAVE; só o MFA fica indisponível.
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    properties = {
      "ballast.registration-open=true",
      "ballast.mfa.key=",
      "ballast.password.memory-kib=8",
      "ballast.password.iterations=1",
      "ballast.password.parallelism=1"
    })
@AutoConfigureMockMvc
class MfaWithoutKeyTest {

  @Autowired MockMvc mvc;

  @Test
  void configureAnswers503() throws Exception {
    TestUsers users = new TestUsers(mvc);
    Cookie session = users.session(users.register());

    MockHttpServletResponse response =
        mvc.perform(
                post("/auth/mfa/configurar")
                    .cookie(session)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"senha\":\"" + TestUsers.PASSWORD + "\"}"))
            .andReturn()
            .getResponse();

    assertThat(response.getStatus()).isEqualTo(503);
    assertThat(response.getContentAsString(StandardCharsets.UTF_8))
        .isEqualTo("{\"erro\":\"mfa indisponível\"}");
  }
}
