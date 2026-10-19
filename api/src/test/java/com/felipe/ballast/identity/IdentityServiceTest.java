package com.felipe.ballast.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.felipe.ballast.IntegrationTest;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class IdentityServiceTest {

  @Autowired UserAccountRepository users;

  // Conta as chamadas sem trocar o algoritmo.
  private static class CountingHasher extends PasswordHasher {
    final AtomicInteger hashCalls = new AtomicInteger();

    CountingHasher() {
      super(new IdentityProperties("false", "", "", new IdentityProperties.Password(8, 1, 1)));
    }

    @Override
    public String hash(String rawPassword) {
      hashCalls.incrementAndGet();
      return super.hash(rawPassword);
    }
  }

  @Test
  void hashesBeforeInsertingSoDuplicateAndNewEmailsCostTheSame() {
    CountingHasher hasher = new CountingHasher();
    IdentityService service = new IdentityService(users, hasher);
    String email = "t" + UUID.randomUUID().toString().replace("-", "") + "@exemplo.test";

    assertThat(service.register(email, "senha-de-teste-12345", null)).isPresent();
    assertThat(service.register(email, "senha-de-teste-12345", null)).isEmpty();

    assertThat(hasher.hashCalls).hasValue(2);
  }
}
