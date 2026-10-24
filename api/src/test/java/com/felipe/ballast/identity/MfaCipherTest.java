package com.felipe.ballast.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MfaCipherTest {

  private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

  private final MfaCipher cipher = new MfaCipher(KEY);
  private final UUID owner = UUID.randomUUID();

  @Test
  void decryptsWhatItEncrypted() {
    byte[] secret = Totp.newSecret();

    byte[] sealed = cipher.encrypt(secret, owner);

    assertThat(sealed).hasSize(MfaCipher.SEALED_BYTES).isNotEqualTo(secret);
    assertThat(cipher.decrypt(sealed, owner)).isEqualTo(secret);
  }

  @Test
  void sameSecretTwiceGetsDifferentNonces() {
    byte[] secret = Totp.newSecret();

    assertThat(cipher.encrypt(secret, owner)).isNotEqualTo(cipher.encrypt(secret, owner));
  }

  // O id do dono entra como dado autenticado: copiar a linha de um usuário para outro não serve.
  @Test
  void refusesTheSecretOfAnotherUser() {
    byte[] sealed = cipher.encrypt(Totp.newSecret(), owner);

    assertThatThrownBy(() -> cipher.decrypt(sealed, UUID.randomUUID()))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void refusesATamperedSecret() {
    byte[] sealed = cipher.encrypt(Totp.newSecret(), owner);
    sealed[20] ^= 1;

    assertThatThrownBy(() -> cipher.decrypt(sealed, owner))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void refusesASecretSealedWithAnotherKey() {
    byte[] other = new byte[32];
    other[0] = 1;
    byte[] sealed =
        new MfaCipher(Base64.getEncoder().encodeToString(other)).encrypt(Totp.newSecret(), owner);

    assertThatThrownBy(() -> cipher.decrypt(sealed, owner))
        .isInstanceOf(IllegalStateException.class);
  }

  // A chave nunca aparece na mensagem de erro.
  @Test
  void refusesAKeyThatIsNotThirtyTwoBytesInBase64() {
    for (String bad :
        new String[] {"curta", Base64.getEncoder().encodeToString(new byte[16]), "@@@@"}) {
      assertThatThrownBy(() -> new MfaCipher(bad))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("MFA_CHAVE deve ser 32 bytes em base64 (openssl rand -base64 32)");
    }
  }

  @Test
  void withoutKeyItIsUnavailable() {
    MfaCipher none = new MfaCipher("");

    assertThat(none.isAvailable()).isFalse();
    assertThat(cipher.isAvailable()).isTrue();
    assertThatThrownBy(() -> none.encrypt(Totp.newSecret(), owner))
        .isInstanceOf(MfaUnavailableException.class);
  }
}
