package com.felipe.ballast.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.felipe.ballast.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

// Mesma regra do nome da conta: code points, e surrogate solto é recusado.
@IntegrationTest
class RegistrationNameTest {

  private static final String EMOJI = "😀";

  @Autowired MockMvc mvc;

  private MockHttpServletResponse registerNamed(String name) throws Exception {
    String body =
        "{\"email\":\""
            + TestUsers.uniqueEmail()
            + "\",\"senha\":\""
            + TestUsers.PASSWORD
            + "\",\"nome\":\""
            + name
            + "\"}";
    return mvc.perform(post("/auth/cadastro").contentType(MediaType.APPLICATION_JSON).content(body))
        .andReturn()
        .getResponse();
  }

  @Test
  void acceptsAHundredEmojis() throws Exception {
    assertThat(registerNamed(EMOJI.repeat(100)).getStatus()).isEqualTo(201);
  }

  @Test
  void rejectsAHundredAndOneEmojis() throws Exception {
    assertThat(registerNamed(EMOJI.repeat(101)).getStatus()).isEqualTo(400);
  }

  @Test
  void rejectsALoneSurrogate() throws Exception {
    assertThat(registerNamed("a\\ud800b").getStatus()).isEqualTo(400);
  }

  @Test
  void rejectsFormatCharactersInTheName() throws Exception {
    assertThat(registerNamed("Fulano\\u200b").getStatus()).isEqualTo(400);
    assertThat(registerNamed("\\u202eFulano").getStatus()).isEqualTo(400);
    assertThat(registerNamed("Ful\\ufeffano").getStatus()).isEqualTo(400);
  }
}
