package com.felipe.ballast.identity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

// Assume entrada já validada e normalizada na borda (RegisterRequest).
@Service
public class IdentityService {

  private static final String EMAIL_UNIQUE_CONSTRAINT = "usuario_email_key";

  // Prazo absoluto, sem renovação a cada uso: token roubado morre em 7 dias mesmo usado todo dia.
  static final Duration SESSION_LIFETIME = Duration.ofDays(7);

  private static final Duration MIN_RETRY = Duration.ofMillis(1);

  record NewSession(String token, Instant expiresAt) {}

  public sealed interface LoginResult {
    // Senha certa, fora do atraso.
    record Success(UserProfile user) implements LoginResult {}

    // E-mail inexistente e senha errada, sem distinguir. failures é a contagem já gravada.
    record BadCredentials(int failures) implements LoginResult {}

    // Atraso em vigor: a senha nem foi verificada e a tentativa não foi contada.
    record Delayed(Duration retryAfter, int failures) implements LoginResult {}
  }

  private final UserAccountRepository users;
  private final LoginSessionRepository sessions;
  private final SessionQueries sessionQueries;
  private final LoginAttempts attempts;
  private final PasswordHasher hasher;
  private final Clock clock;
  private final TransactionTemplate transaction;
  private final ApplicationEventPublisher events;

  IdentityService(
      UserAccountRepository users,
      LoginSessionRepository sessions,
      SessionQueries sessionQueries,
      LoginAttempts attempts,
      PasswordHasher hasher,
      Clock clock,
      TransactionTemplate transaction,
      ApplicationEventPublisher events) {
    this.users = users;
    this.sessions = sessions;
    this.sessionQueries = sessionQueries;
    this.attempts = attempts;
    this.hasher = hasher;
    this.clock = clock;
    this.transaction = transaction;
    this.events = events;
  }

  // Vazio quando o e-mail já existe. O usuário e as sementes de categoria (evento síncrono)
  // entram na mesma transação, aberta com TransactionTemplate e não com @Transactional: a
  // violação de unicidade sai do template já com o rollback feito e é capturada aqui fora. Com
  // @Transactional ela marcaria a transação como rollback-only e o chamador veria
  // UnexpectedRollbackException em vez do Optional vazio.
  // O hash é calculado antes do INSERT para e-mail novo e e-mail repetido custarem o mesmo tempo.
  public Optional<UserProfile> register(String email, String password, String name) {
    String hash = hasher.hash(password);
    try {
      UserAccount saved =
          transaction.execute(
              status -> {
                UserAccount user = users.saveAndFlush(new UserAccount(email, hash, name));
                events.publishEvent(new UserRegistered(user.getId()));
                return user;
              });
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

  // A tentativa é reservada (contada) antes do argon2, num único comando: é isso que faz
  // tentativas simultâneas contarem uma a uma. Se o banco falhar depois da reserva, o contador
  // já subiu e fica assim (fail-closed). No atraso a senha não é verificada nem contada, senão
  // quem digita o e-mail alheio manteria o dono trancado. No e-mail inexistente roda uma
  // verificação fictícia para o tempo de resposta não denunciar a conta. Sem @Transactional: o
  // argon2 leva centenas de ms e não deve segurar conexão do pool.
  public LoginResult authenticate(String email, String password) {
    OptionalInt reserved = attempts.reserve(email, clock.instant());
    if (reserved.isEmpty()) {
      return delayedResult(email);
    }
    int failures = reserved.getAsInt();

    Optional<UserAccount> account = users.findByEmail(email);
    if (account.isEmpty()) {
      hasher.matchesDummy(password);
      return new LoginResult.BadCredentials(failures);
    }
    if (!hasher.matches(password, account.get().getPasswordHash())) {
      return new LoginResult.BadCredentials(failures);
    }
    attempts.clear(email);
    return new LoginResult.Success(profileOf(account.get()));
  }

  private LoginResult delayedResult(String email) {
    Optional<LoginAttempts.Delay> delay = attempts.findDelay(email, clock.instant());
    // O atraso pode vencer entre a recusa e a leitura (ou a linha sumir num login com sucesso);
    // recusado é recusado, com o mínimo de 1 ms.
    Duration remaining = delay.map(LoginAttempts.Delay::remaining).orElse(Duration.ZERO);
    if (remaining.compareTo(MIN_RETRY) < 0) {
      remaining = MIN_RETRY;
    }
    return new LoginResult.Delayed(remaining, delay.map(LoginAttempts.Delay::failures).orElse(0));
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

  // Vazio para token desconhecido, expirado ou revogado, sem distinguir.
  public Optional<UUID> findUserIdBySession(String token) {
    return sessionQueries.findActiveUserId(SessionTokens.hash(token), clock.instant());
  }

  // Devolve o dono da sessão revogada, ou vazio se não havia sessão ativa com esse token.
  @Transactional
  public Optional<UUID> revokeSession(String token) {
    return sessionQueries.revoke(SessionTokens.hash(token), clock.instant());
  }

  public Optional<UserProfile> findProfile(UUID userId) {
    return users.findById(userId).map(IdentityService::profileOf);
  }

  private static UserProfile profileOf(UserAccount account) {
    return new UserProfile(account.getId(), account.getEmail(), account.getName());
  }
}
