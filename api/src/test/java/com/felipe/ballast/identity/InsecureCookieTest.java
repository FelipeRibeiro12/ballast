package com.felipe.ballast.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.felipe.ballast.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

// Só desenvolvimento aceita COOKIE_SECURE=false (Safari recusa cookie Secure sobre HTTP).
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    properties = {
      "ballast.registration-open=true",
      "ballast.cookie-secure=false",
      "ballast.app-env=development",
      "ballast.password.memory-kib=8",
      "ballast.password.iterations=1",
      "ballast.password.parallelism=1"
    })
@AutoConfigureMockMvc
class InsecureCookieTest {

  @Autowired MockMvc mvc;

  @Test
  void loginCookieHasNoSecureButKeepsTheOtherAttributes() throws Exception {
    TestUsers users = new TestUsers(mvc);
    TestUsers.Registered user = users.register();

    MockHttpServletResponse response = users.login(user.email(), TestUsers.PASSWORD);

    assertThat(response.getHeader("Set-Cookie"))
        .doesNotContain("Secure")
        .contains("HttpOnly")
        .contains("SameSite=Lax")
        .contains("Path=/")
        .contains("Max-Age=604800");
  }
}
