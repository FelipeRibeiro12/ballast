package com.felipe.ballast.accounts;

import static org.assertj.core.api.Assertions.assertThat;

import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.identity.TestUsers;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

// Só a forma canônica de 36 caracteres vale como id. UUID.fromString também lê "1-1-1-1-1".
@IntegrationTest
class AccountIdFormatTest {

  private static final UUID SHORT_FORM_TARGET =
      UUID.fromString("00000001-0001-0001-0001-000000000001");

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  private AccountsApi api;
  private Cookie session;
  private TestUsers.Registered owner;

  @BeforeEach
  void setUp() throws Exception {
    api = new AccountsApi(mvc);
    TestUsers users = new TestUsers(mvc);
    owner = users.register();
    session = users.session(owner);
  }

  @Test
  void shortFormOfAnExistingIdIs404() throws Exception {
    jdbc.sql(
            "INSERT INTO conta (id, usuario_id, nome, tipo) VALUES (:id, :usuario, 'Curta',"
                + " 'corrente')")
        .param("id", SHORT_FORM_TARGET)
        .param("usuario", owner.id())
        .update();

    assertThat(api.find(session, SHORT_FORM_TARGET.toString()).getStatus()).isEqualTo(200);
    assertThat(api.find(session, "1-1-1-1-1").getStatus()).isEqualTo(404);
    assertThat(api.setArchived(session, "1-1-1-1-1", true).getStatus()).isEqualTo(404);
  }

  @Test
  void upperCaseCanonicalIdIsAccepted() throws Exception {
    String id = api.createOk(session, "Maiúscula").get("id").asString();

    assertThat(api.find(session, id.toUpperCase()).getStatus()).isEqualTo(200);
    assertThat(api.setArchived(session, id.toUpperCase(), true).getStatus()).isEqualTo(200);
  }
}
