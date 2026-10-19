package com.felipe.ballast.categories;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.identity.IdentityService;
import com.felipe.ballast.identity.UserProfile;
import com.felipe.ballast.identity.UserRegistered;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;

// O cadastro cria o usuário e as 16 sementes na mesma transação.
@IntegrationTest
@Import(RegistrationSeedTest.FailingListener.class)
class RegistrationSeedTest {

  private static final String PASSWORD = "senha-de-teste-12345";

  // Escuta o mesmo evento do módulo de categorias e falha sob demanda, depois de as sementes
  // entrarem ou antes, conforme a ordem dos listeners: nos dois casos nada pode ficar gravado.
  @TestConfiguration
  static class FailingListener {
    static final AtomicBoolean FAIL = new AtomicBoolean();

    @EventListener
    void fail(UserRegistered event) {
      if (FAIL.get()) {
        throw new IllegalStateException("falha simulada");
      }
    }
  }

  @Autowired IdentityService identity;
  @Autowired JdbcClient jdbc;

  @AfterEach
  void reset() {
    FailingListener.FAIL.set(false);
  }

  private static String uniqueEmail() {
    return "t" + UUID.randomUUID().toString().replace("-", "") + "@exemplo.test";
  }

  private long countCategories() {
    return jdbc.sql("SELECT count(*) FROM categoria").query(Long.class).single();
  }

  private long countUsersByEmail(String email) {
    return jdbc.sql("SELECT count(*) FROM usuario WHERE email = :email")
        .param("email", email)
        .query(Long.class)
        .single();
  }

  @Test
  void aNewUserGetsExactlyTheSixteenSeeds() {
    UserProfile user = identity.register(uniqueEmail(), PASSWORD, null).orElseThrow();

    List<String> stored =
        jdbc.sql(
                "SELECT tipo || ':' || nome FROM categoria WHERE usuario_id = :id"
                    + " ORDER BY tipo, nome")
            .param("id", user.id())
            .query(String.class)
            .list();
    List<String> expected =
        CategorySeeds.ALL.stream()
            .map(seed -> seed.type().value() + ":" + seed.name())
            .sorted()
            .toList();
    assertThat(stored).hasSize(16).containsExactlyElementsOf(expected);
  }

  @Test
  void aDuplicateEmailStaysAConflictAndSeedsNothing() {
    String email = uniqueEmail();
    identity.register(email, PASSWORD, null).orElseThrow();
    long categoriesBefore = countCategories();

    assertThat(identity.register(email, PASSWORD, null)).isEmpty();

    assertThat(countCategories()).isEqualTo(categoriesBefore);
    assertThat(countUsersByEmail(email)).isEqualTo(1);
  }

  @Test
  void ifASeedListenerFailsTheUserIsNotCreatedAndNothingIsLeftBehind() {
    String email = uniqueEmail();
    long categoriesBefore = countCategories();
    FailingListener.FAIL.set(true);

    assertThatThrownBy(() -> identity.register(email, PASSWORD, null))
        .isInstanceOf(IllegalStateException.class);

    assertThat(countUsersByEmail(email)).isZero();
    assertThat(countCategories()).isEqualTo(categoriesBefore);
  }
}
