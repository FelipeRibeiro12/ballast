package com.felipe.ballast.identity;

import java.util.Optional;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

// Assume entrada já validada e normalizada na borda (RegisterRequest).
@Service
public class IdentityService {

  private static final String EMAIL_UNIQUE_CONSTRAINT = "usuario_email_key";

  private final UserAccountRepository users;
  private final PasswordHasher hasher;

  IdentityService(UserAccountRepository users, PasswordHasher hasher) {
    this.users = users;
    this.hasher = hasher;
  }

  // Vazio quando o e-mail já existe. Sem @Transactional de propósito: o saveAndFlush abre a
  // própria transação, e a violação de unicidade precisa ser capturada fora dela (dentro, a
  // transação ficaria marcada para rollback e o chamador veria UnexpectedRollbackException).
  // O hash é calculado antes do INSERT para e-mail novo e e-mail repetido custarem o mesmo tempo.
  public Optional<UserProfile> register(String email, String password, String name) {
    String hash = hasher.hash(password);
    try {
      UserAccount saved = users.saveAndFlush(new UserAccount(email, hash, name));
      return Optional.of(new UserProfile(saved.getId(), saved.getEmail(), saved.getName()));
    } catch (DataIntegrityViolationException e) {
      if (isDuplicateEmail(e)) {
        return Optional.empty();
      }
      throw e;
    }
  }

  private static boolean isDuplicateEmail(Throwable error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof ConstraintViolationException violation
          && EMAIL_UNIQUE_CONSTRAINT.equals(violation.getConstraintName())) {
        return true;
      }
    }
    return false;
  }
}
