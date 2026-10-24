package com.felipe.ballast.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.felipe.ballast.IntegrationTest;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

@IntegrationTest
class UserSeederTest {

  private static final String PASSWORD = "senha-de-teste-12345";

  @Autowired UserSeeder seeder;
  @Autowired IdentityService identity;
  @Autowired JdbcClient jdbc;

  private static String uniqueEmail() {
    return "t" + UUID.randomUUID().toString().replace("-", "") + "@exemplo.test";
  }

  private int countByEmail(String email) {
    return jdbc.sql("SELECT count(*) FROM usuario WHERE email = :email")
        .param("email", email)
        .query(Integer.class)
        .single();
  }

  @Test
  void createsTheInitialUserWhoCanThenLogIn() {
    String email = uniqueEmail();

    UserSeeder.Result result = seeder.seed(email, PASSWORD, "Fulano");

    assertThat(result.created()).isTrue();
    UserProfile user =
        ((IdentityService.LoginResult.Success) identity.authenticate(email, PASSWORD, "127.0.0.1"))
            .user();
    assertThat(user.id()).isEqualTo(result.userId());
    assertThat(user.name()).isEqualTo("Fulano");
  }

  @Test
  void normalizesTheEmailLikeTheRegistrationDoes() {
    String email = uniqueEmail();

    seeder.seed("  " + email.toUpperCase() + " ", PASSWORD, null);

    assertThat(countByEmail(email)).isEqualTo(1);
  }

  @Test
  void runningTwiceIsANoOpAndKeepsTheFirstPassword() {
    String email = uniqueEmail();
    seeder.seed(email, PASSWORD, null);

    UserSeeder.Result second = seeder.seed(email, "outra-senha-12345", null);

    assertThat(second.created()).isFalse();
    assertThat(countByEmail(email)).isEqualTo(1);
    assertThat(identity.authenticate(email, PASSWORD, "127.0.0.1"))
        .isInstanceOf(IdentityService.LoginResult.Success.class);
    assertThat(identity.authenticate(email, "outra-senha-12345", "127.0.0.1"))
        .isInstanceOf(IdentityService.LoginResult.BadCredentials.class);
  }

  static Stream<Arguments> invalidInputs() {
    String valid = "a@exemplo.test";
    return Stream.of(
        Arguments.of("e-mail sem arroba", "exemplo.test", PASSWORD, null, "SEED_EMAIL"),
        Arguments.of("e-mail com NUL", "a\u0000b@exemplo.test", PASSWORD, null, "SEED_EMAIL"),
        Arguments.of(
            "e-mail com quebra de linha", "a\nb@exemplo.test", PASSWORD, null, "SEED_EMAIL"),
        Arguments.of(
            "e-mail de 255", "a".repeat(242) + "@exemplo.test", PASSWORD, null, "SEED_EMAIL"),
        Arguments.of("senha de 11", valid, "a".repeat(11), null, "SEED_SENHA"),
        Arguments.of("senha de 129", valid, "a".repeat(129), null, "SEED_SENHA"),
        Arguments.of("nome de 101", valid, PASSWORD, "a".repeat(101), "SEED_NOME"),
        Arguments.of("nome com controle", valid, PASSWORD, "Fu\u0007lano", "SEED_NOME"),
        Arguments.of("e-mail ausente", "", PASSWORD, null, "SEED_EMAIL"),
        Arguments.of("senha ausente", valid, "", null, "SEED_SENHA"));
  }

  // Mesma validação do cadastro pela API (critério da F1). A mensagem nomeia a variável e nunca
  // devolve o valor: o e-mail e a senha não vão para a saída nem para os erros.
  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidInputs")
  void rejectsWhatTheRegistrationRejectsWithoutEchoingTheValues(
      String name, String email, String password, String userName, String variable) {
    assertThatThrownBy(() -> seeder.seed(email, password, userName))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(variable)
        .hasMessageNotContaining(password.isEmpty() ? "\u0001" : password)
        .hasMessageNotContaining(email.isEmpty() ? "\u0001" : email);
  }

  @Test
  void acceptsPasswordWithControlCharactersLikeTheRegistration() {
    assertThat(seeder.seed(uniqueEmail(), "senha\u0000com\u0007controle\n123", null).created())
        .isTrue();
  }
}
