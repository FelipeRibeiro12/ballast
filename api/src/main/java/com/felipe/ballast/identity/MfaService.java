package com.felipe.ballast.identity;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

// Segundo fator por TOTP (ADR-050). O usuário vem sempre da sessão ou do desafio, nunca da
// requisição.
@Service
class MfaService {

  private static final Logger log = LoggerFactory.getLogger(MfaService.class);

  static final Duration CHALLENGE_LIFETIME = Duration.ofMinutes(5);
  static final int RECOVERY_CODES = 10;
  // Sem 0, 1, i, l e o, que se confundem lidos no papel. 31^10 dá uns 50 bits por código.
  private static final String RECOVERY_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";
  private static final int RECOVERY_LENGTH = 10;
  private static final Duration MIN_RETRY = Duration.ofSeconds(1);
  private static final SecureRandom RANDOM = new SecureRandom();

  record Status(boolean active, int remainingRecoveryCodes) {}

  record Setup(String secret, String uri) {}

  private final MfaQueries queries;
  private final SessionQueries sessionQueries;
  private final MfaCipher cipher;
  private final UserAccountRepository users;
  private final PasswordHasher hasher;
  private final Clock clock;
  private final TransactionTemplate transaction;

  MfaService(
      MfaQueries queries,
      SessionQueries sessionQueries,
      MfaCipher cipher,
      UserAccountRepository users,
      PasswordHasher hasher,
      Clock clock,
      TransactionTemplate transaction) {
    this.queries = queries;
    this.sessionQueries = sessionQueries;
    this.cipher = cipher;
    this.users = users;
    this.hasher = hasher;
    this.clock = clock;
    this.transaction = transaction;
  }

  Status status(UUID userId) {
    boolean active = queries.find(userId).map(MfaQueries.Row::active).orElse(false);
    return new Status(active, active ? queries.remainingRecoveryCodes(userId) : 0);
  }

  boolean isActive(UUID userId) {
    return status(userId).active();
  }

  // Pede a senha para quem roubou só a sessão não ligar o MFA e trancar o dono do lado de fora.
  Setup configure(UUID userId, String password) {
    if (!cipher.isAvailable()) {
      throw MfaError.of(HttpStatus.SERVICE_UNAVAILABLE, "mfa indisponível");
    }
    UserAccount account = checkPassword(userId, password);
    byte[] secret = Totp.newSecret();
    if (queries.savePending(userId, cipher.encrypt(secret, userId)) == 0) {
      throw MfaError.of(HttpStatus.CONFLICT, "mfa já ativo");
    }
    String encoded = Totp.base32(secret);
    String label =
        URLEncoder.encode(account.getEmail(), StandardCharsets.UTF_8).replace("+", "%20");
    return new Setup(
        encoded,
        "otpauth://totp/Ballast:"
            + label
            + "?secret="
            + encoded
            + "&issuer=Ballast&algorithm=SHA1&digits=6&period=30");
  }

  // Só o código do autenticador ativa: é a prova de que o segredo foi lido certo. Os códigos de
  // recuperação só existem em claro nesta resposta.
  List<String> activate(UUID userId, String code, byte[] currentSessionHash) {
    MfaQueries.Row row =
        queries
            .find(userId)
            .orElseThrow(() -> MfaError.of(HttpStatus.CONFLICT, "configure o mfa antes"));
    if (row.active()) {
      throw MfaError.of(HttpStatus.CONFLICT, "mfa já ativo");
    }
    Instant now = clock.instant();
    reserve(userId, now);
    OptionalLong step = matchTotp(userId, row, normalize(code), now);
    List<String> codes = newRecoveryCodes();
    boolean activated =
        step.isPresent()
            && Boolean.TRUE.equals(
                transaction.execute(
                    status -> {
                      if (!queries.activate(userId, step.getAsLong(), now)) {
                        return false;
                      }
                      queries.replaceRecoveryCodes(
                          userId, codes.stream().map(MfaService::hashOf).toList());
                      queries.clearFailures(userId);
                      // Na mesma transação: ou liga e derruba as outras, ou nada muda.
                      int revoked = sessionQueries.revokeOthers(userId, currentSessionHash, now);
                      log.info(
                          "mfa: sessões revogadas na ativação usuario_id={} total={}",
                          userId,
                          revoked);
                      return true;
                    }));
    if (!activated) {
      throw MfaError.of(HttpStatus.BAD_REQUEST, "código inválido");
    }
    return codes.stream().map(MfaService::format).toList();
  }

  // Só o código do app, e não um de recuperação: quem tem só um código de recuperação (ou só a
  // sessão) não renova a lista. Gastar o código antigo e trocar a lista são uma transação só.
  List<String> regenerateRecoveryCodes(UUID userId, String code) {
    if (!isActive(userId)) {
      throw MfaError.of(HttpStatus.CONFLICT, "mfa não está ativo");
    }
    List<String> codes = newRecoveryCodes();
    Boolean replaced =
        transaction.execute(
            status -> {
              if (!verify(userId, code, false)) {
                return false;
              }
              queries.replaceRecoveryCodes(userId, codes.stream().map(MfaService::hashOf).toList());
              return true;
            });
    if (!Boolean.TRUE.equals(replaced)) {
      throw MfaError.of(HttpStatus.BAD_REQUEST, "código inválido");
    }
    return codes.stream().map(MfaService::format).toList();
  }

  // Senha e código: quem tem só a sessão, ou só o celular, não desliga.
  void disable(UUID userId, String password, String code) {
    if (!isActive(userId)) {
      throw MfaError.of(HttpStatus.CONFLICT, "mfa não está ativo");
    }
    checkPassword(userId, password);
    if (!verify(userId, code)) {
      throw MfaError.of(HttpStatus.BAD_REQUEST, "código inválido");
    }
    queries.delete(userId);
  }

  // O token em claro só existe aqui e no cookie, como o da sessão (ADR-015).
  String startChallenge(UUID userId) {
    String token = SessionTokens.generate();
    queries.createChallenge(
        userId, SessionTokens.hash(token), clock.instant().plus(CHALLENGE_LIFETIME));
    return token;
  }

  // Devolve o dono do desafio, já consumido. Código errado mantém o desafio para outra tentativa
  // dentro dos 5 minutos; o atraso por usuário é que segura a força bruta.
  UUID answerChallenge(String token, String code) {
    byte[] tokenHash = SessionTokens.hash(token);
    UUID userId =
        queries
            .findChallenge(tokenHash, clock.instant())
            .orElseThrow(
                () -> MfaError.of(HttpStatus.UNAUTHORIZED, "desafio expirado ou inválido"));
    if (!verify(userId, code)) {
      throw MfaError.of(HttpStatus.UNAUTHORIZED, "código inválido");
    }
    if (!queries.consumeChallenge(tokenHash, userId)) {
      throw MfaError.of(HttpStatus.UNAUTHORIZED, "desafio expirado ou inválido");
    }
    return userId;
  }

  // A tentativa é contada antes de conferir, como no login: fail-closed.
  private boolean verify(UUID userId, String code) {
    return verify(userId, code, true);
  }

  private boolean verify(UUID userId, String code, boolean acceptRecoveryCode) {
    MfaQueries.Row row =
        queries
            .find(userId)
            .filter(MfaQueries.Row::active)
            .orElseThrow(() -> MfaError.of(HttpStatus.CONFLICT, "mfa não está ativo"));
    Instant now = clock.instant();
    reserve(userId, now);
    String normalized = normalize(code);
    boolean valid;
    if (acceptRecoveryCode && normalized.length() == RECOVERY_LENGTH) {
      valid = queries.consumeRecoveryCode(userId, hashOf(normalized), now);
      if (valid) {
        log.warn(
            "mfa: código de recuperação usado usuario_id={} restantes={}",
            userId,
            queries.remainingRecoveryCodes(userId));
      }
    } else {
      OptionalLong step = matchTotp(userId, row, normalized, now);
      valid = step.isPresent() && queries.acceptStep(userId, step.getAsLong());
    }
    if (valid) {
      queries.clearFailures(userId);
    }
    return valid;
  }

  private void reserve(UUID userId, Instant now) {
    OptionalInt reserved = queries.reserve(userId, now);
    if (reserved.isEmpty()) {
      Duration remaining =
          queries.findDelay(userId, now).map(MfaQueries.Delay::remaining).orElse(MIN_RETRY);
      throw MfaError.delayed(remaining.compareTo(MIN_RETRY) < 0 ? MIN_RETRY : remaining);
    }
  }

  // Sem MFA_CHAVE o segredo não abre: só o código de recuperação entra.
  private OptionalLong matchTotp(UUID userId, MfaQueries.Row row, String code, Instant now) {
    if (!cipher.isAvailable()) {
      log.warn("mfa: MFA_CHAVE ausente, código do autenticador recusado usuario_id={}", userId);
      return OptionalLong.empty();
    }
    return Totp.match(cipher.decrypt(row.sealedSecret(), userId), code, now);
  }

  private UserAccount checkPassword(UUID userId, String password) {
    UserAccount account =
        users
            .findById(userId)
            .orElseThrow(() -> MfaError.of(HttpStatus.UNAUTHORIZED, "não autenticado"));
    if (!hasher.matches(password, account.getPasswordHash())) {
      throw MfaError.of(HttpStatus.BAD_REQUEST, "senha incorreta");
    }
    return account;
  }

  // Aceita o código como o usuário copiar: com espaço, hífen ou maiúscula.
  private static String normalize(String code) {
    return code.replaceAll("[\\s-]", "").toLowerCase(Locale.ROOT);
  }

  private static List<String> newRecoveryCodes() {
    List<String> codes = new ArrayList<>();
    for (int i = 0; i < RECOVERY_CODES; i++) {
      codes.add(newRecoveryCode());
    }
    return codes;
  }

  private static String format(String recoveryCode) {
    return recoveryCode.substring(0, 5) + "-" + recoveryCode.substring(5);
  }

  private static String newRecoveryCode() {
    StringBuilder code = new StringBuilder(RECOVERY_LENGTH);
    for (int i = 0; i < RECOVERY_LENGTH; i++) {
      code.append(RECOVERY_ALPHABET.charAt(RANDOM.nextInt(RECOVERY_ALPHABET.length())));
    }
    return code.toString();
  }

  private static byte[] hashOf(String recoveryCode) {
    return SessionTokens.hash(recoveryCode);
  }
}
