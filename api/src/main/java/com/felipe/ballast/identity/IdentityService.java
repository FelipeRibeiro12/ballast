package com.felipe.ballast.identity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Assume entrada já validada e normalizada na borda (RegisterRequest).
@Service
public class IdentityService {

  private static final String EMAIL_UNIQUE_CONSTRAINT = "usuario_email_key";

  // Prazo absoluto, sem renovação a cada uso: token roubado morre em 7 dias mesmo usado todo dia.
  static final Duration SESSION_LIFETIME = Duration.ofDays(7);

  record NewSession(String token, Instant expiresAt) {}

  private final UserAccountRepository users;
  private final LoginSessionRepository sessions;
  private final PasswordHasher hasher;
  private final Clock clock;

  IdentityService(
      UserAccountRepository users,
      LoginSessionRepository sessions,
      PasswordHasher hasher,
      Clock clock) {
    this.users = users;
    this.sessions = sessions;
    this.hasher = hasher;
    this.clock = clock;
  }

  // Vazio quando o e-mail já existe. Sem @Transactional de propósito: o saveAndFlush abre a
  // própria transação, e a violação de unicidade precisa ser capturada fora dela (dentro, a
  // transação ficaria marcada para rollback e o chamador veria UnexpectedRollbackException).
  // O hash é calculado antes do INSERT para e-mail novo e e-mail repetido custarem o mesmo tempo.
  public Optional<UserProfile> register(String email, String password, String name) {
    String hash = hasher.hash(password);
    try {
      UserAccount saved = users.saveAndFlush(new UserAccount(email, hash, name));
      return Optional.of(profileOf(saved));
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

  // Vazio cobre e-mail inexistente e senha errada sem distinguir. No inexistente roda uma
  // verificação fictícia para o tempo de resposta não denunciar a conta. Sem @Transactional: o
  // argon2 leva centenas de ms e não deve segurar conexão do pool.
  public Optional<UserProfile> authenticate(String email, String password) {
    Optional<UserAccount> account = users.findByEmail(email);
    if (account.isEmpty()) {
      hasher.matchesDummy(password);
      return Optional.empty();
    }
    if (!hasher.matches(password, account.get().getPasswordHash())) {
      return Optional.empty();
    }
    return account.map(IdentityService::profileOf);
  }

  // Devolve o token em claro, que só existe aqui e no cookie.
  @Transactional
  public NewSession startSession(UUID userId) {
    String token = SessionTokens.generate();
    Instant expiresAt = clock.instant().plus(SESSION_LIFETIME);
    sessions.save(
        new LoginSession(users.getReferenceById(userId), SessionTokens.hash(token), expiresAt));
    return new NewSession(token, expiresAt);
  }

  private static UserProfile profileOf(UserAccount account) {
    return new UserProfile(account.getId(), account.getEmail(), account.getName());
  }
}
