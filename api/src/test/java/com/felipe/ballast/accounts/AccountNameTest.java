package com.felipe.ballast.accounts;

import static org.assertj.core.api.Assertions.assertThat;

import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.identity.TestUsers;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

// O limite do nome conta code points, como o char_length do CHECK do banco, e texto que o
// Postgres trocaria por "?" é recusado na borda.
@IntegrationTest
class AccountNameTest {

  private static final String EMOJI = "😀";

  @Autowired MockMvc mvc;
  private AccountsApi api;
  private Cookie session;

  @BeforeEach
  void setUp() throws Exception {
    api = new AccountsApi(mvc);
    TestUsers users = new TestUsers(mvc);
    session = users.session(users.register());
  }

  private MockHttpServletResponse createNamed(String name) throws Exception {
    return api.create(session, "{\"nome\":\"" + name + "\",\"tipo\":\"corrente\"}");
  }

  @Test
  void acceptsAHundredEmojisAndReturnsThemIntact() throws Exception {
    MockHttpServletResponse response = createNamed(EMOJI.repeat(100));

    assertThat(response.getStatus()).isEqualTo(201);
    assertThat(AccountsApi.body(response).get("nome").asString()).isEqualTo(EMOJI.repeat(100));
  }

  @Test
  void rejectsAHundredAndOneEmojis() throws Exception {
    assertThat(createNamed(EMOJI.repeat(101)).getStatus()).isEqualTo(400);
  }

  @Test
  void rejectsALoneHighSurrogate() throws Exception {
    // Dois backslashes no fonte: o JSON recebe a sequência de escape, não o caractere.
    assertThat(createNamed("a\\ud800b").getStatus()).isEqualTo(400);
  }

  @Test
  void rejectsALoneLowSurrogate() throws Exception {
    assertThat(createNamed("a\\udc00b").getStatus()).isEqualTo(400);
  }

  @Test
  void acceptsAnEscapedSurrogatePair() throws Exception {
    MockHttpServletResponse response = createNamed("a\\ud83d\\ude00b");

    assertThat(response.getStatus()).isEqualTo(201);
    assertThat(AccountsApi.body(response).get("nome").asString()).isEqualTo("a" + EMOJI + "b");
  }

  @Test
  void acceptsAnAssignedLetterOutsideTheBmpWithSurrogateLookalikeLowBits() throws Exception {
    // U+1D800 tem os 16 bits de baixo em D800, mas é uma letra atribuída.
    String letter = new String(Character.toChars(0x1D800));

    MockHttpServletResponse response = createNamed(letter + "a");

    assertThat(response.getStatus()).isEqualTo(201);
    assertThat(AccountsApi.body(response).get("nome").asString()).isEqualTo(letter + "a");
  }
}
