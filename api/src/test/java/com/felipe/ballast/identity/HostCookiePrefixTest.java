package com.felipe.ballast.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.felipe.ballast.TestcontainersConfiguration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

// Com Secure ligado o cookie leva __Host-: o navegador só aceita se vier com Secure, Path=/ e sem
// Domain, e um subdomínio não consegue sobrescrevê-lo.
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    properties = {
      "ballast.registration-open=true",
      "ballast.password.memory-kib=8",
      "ballast.password.iterations=1",
      "ballast.password.parallelism=1"
    })
@AutoConfigureMockMvc
class HostCookiePrefixTest {

  @Autowired MockMvc mvc;

  @Test
  void sessionCookieHasHostPrefixAndTheAttributesItRequires() throws Exception {
    TestUsers users = new TestUsers(mvc);
    TestUsers.Registered user = users.register();

    MockHttpServletResponse response = users.login(user.email(), TestUsers.PASSWORD);

    assertThat(response.getHeader("Set-Cookie"))
        .startsWith("__Host-ballast_sessao=")
        .contains("Secure")
        .contains("Path=/")
        .contains("HttpOnly")
        .doesNotContain("Domain");
  }

  @Test
  void logoutExpiresTheCookieWithTheSameName() throws Exception {
    MockHttpServletResponse response =
        mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/auth/logout"))
            .andReturn()
            .getResponse();

    List<String> cookies = response.getHeaders("Set-Cookie");
    assertThat(cookies).singleElement().asString().startsWith("__Host-ballast_sessao=;");
  }

  @Test
  void theOldCookieNameDoesNotOpenASession() throws Exception {
    TestUsers users = new TestUsers(mvc);
    TestUsers.Registered user = users.register();
    String token = users.session(user).getValue();

    int status =
        mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/auth/eu")
                    .cookie(new jakarta.servlet.http.Cookie("ballast_sessao", token)))
            .andReturn()
            .getResponse()
            .getStatus();

    assertThat(status).isEqualTo(401);
  }
}
