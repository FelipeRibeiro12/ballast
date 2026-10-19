package com.felipe.ballast.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.felipe.ballast.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@Import(TestcontainersConfiguration.class)
// api-docs ligado como no desenvolvimento; o caso desligado está em ApiDocsDisabledTest.
@SpringBootTest(properties = "springdoc.api-docs.enabled=true")
@AutoConfigureMockMvc
class SecurityBaseTest {

  private static final String NOT_AUTHENTICATED = "{\"erro\":\"não autenticado\"}";

  @Autowired MockMvc mvc;

  @Test
  void protectedRouteWithoutSessionAnswers401WithJsonBody() throws Exception {
    mvc.perform(get("/qualquer-rota"))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(content().json(NOT_AUTHENTICATED, true));
  }

  @Test
  void stateChangingRouteWithoutSessionAnswers401NotCsrf403() throws Exception {
    mvc.perform(post("/qualquer-rota").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isUnauthorized())
        .andExpect(content().json(NOT_AUTHENTICATED, true));
  }

  @Test
  void healthzStaysPublic() throws Exception {
    mvc.perform(get("/healthz")).andExpect(status().isOk());
  }

  @Test
  void healthzAnswersHeadToo() throws Exception {
    mvc.perform(head("/healthz")).andExpect(status().isOk());
  }

  @Test
  void apiDocsStayPublicWhileEnabled() throws Exception {
    mvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
    mvc.perform(get("/v3/api-docs.yaml")).andExpect(status().isOk());
  }

  @Test
  void statelessSessionNeverSetsJsessionid() throws Exception {
    mvc.perform(get("/healthz")).andExpect(header().doesNotExist("Set-Cookie"));
  }

  @Test
  void defaultSecurityHeadersAreOn() throws Exception {
    mvc.perform(get("/healthz"))
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(header().string("X-Frame-Options", "DENY"))
        .andExpect(
            header().string("Cache-Control", "no-cache, no-store, max-age=0, must-revalidate"));
  }
}
