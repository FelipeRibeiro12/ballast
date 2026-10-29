package com.felipe.ballast.wallets;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

// Prova de posse por assinatura (ADR-072). Nenhum log daqui escreve endereço, mensagem, nonce ou
// assinatura: só usuario_id e carteira_id.
@Service
class PossessionService {

  private static final Logger log = LoggerFactory.getLogger(PossessionService.class);

  static final Duration TTL = Duration.ofMinutes(5);
  static final Duration MIN_INTERVAL = Duration.ofSeconds(5);
  private static final int NONCE_BYTES = 16;

  record Issued(String nonce, String message, Instant expiresAt) {}

  enum Rejected {
    NOT_FOUND,
    TOO_SOON,
    EXPIRED,
    INVALID_SIGNATURE
  }

  sealed interface ChallengeResult {
    record Ok(Issued challenge) implements ChallengeResult {}

    record No(Rejected reason) implements ChallengeResult {}
  }

  sealed interface VerifyResult {
    record Ok(WalletService.View view) implements VerifyResult {}

    record No(Rejected reason) implements VerifyResult {}
  }

  private final WalletQueries wallets;
  private final WalletService walletService;
  private final PossessionQueries queries;
  private final PosseProperties posse;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  PossessionService(
      WalletQueries wallets,
      WalletService walletService,
      PossessionQueries queries,
      PosseProperties posse,
      Clock clock) {
    this.wallets = wallets;
    this.walletService = walletService;
    this.queries = queries;
    this.posse = posse;
    this.clock = clock;
  }

  ChallengeResult challenge(UUID userId, UUID walletId) {
    Optional<WalletQueries.Wallet> wallet = wallets.findWallet(userId, walletId);
    if (wallet.isEmpty()) {
      return new ChallengeResult.No(Rejected.NOT_FOUND);
    }
    Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
    Instant expiresAt = now.plus(TTL);
    String nonce = newNonce();
    String message =
        PossessionMessage.build(
            wallet.get().network(), wallet.get().address(), posse, nonce, now, expiresAt);
    try {
      // Desafio emitido há MIN_INTERVAL ou mais vence até aqui; mais novo que isso é recusado.
      boolean stored =
          queries.upsertChallenge(
              userId, walletId, nonce, message, expiresAt, now.plus(TTL).minus(MIN_INTERVAL));
      if (!stored) {
        log.warn(
            "posse: desafio recusado por frequência usuario_id={} carteira_id={}",
            userId,
            walletId);
        return new ChallengeResult.No(Rejected.TOO_SOON);
      }
    } catch (DataIntegrityViolationException e) {
      // A carteira foi apagada entre a leitura e a gravação.
      return new ChallengeResult.No(Rejected.NOT_FOUND);
    }
    log.info("posse: desafio emitido usuario_id={} carteira_id={}", userId, walletId);
    return new ChallengeResult.Ok(new Issued(nonce, message, expiresAt));
  }

  VerifyResult verify(UUID userId, UUID walletId, String nonce, String signature) {
    Optional<WalletQueries.Wallet> wallet = wallets.findWallet(userId, walletId);
    if (wallet.isEmpty()) {
      return new VerifyResult.No(Rejected.NOT_FOUND);
    }
    // Consome antes de olhar a assinatura: palpite errado também queima o desafio.
    Optional<PossessionQueries.Challenge> stored = queries.consumeChallenge(userId, walletId);
    Instant now = clock.instant();
    if (stored.isEmpty() || now.isAfter(stored.get().expiresAt())) {
      log.warn("posse: desafio ausente ou vencido usuario_id={} carteira_id={}", userId, walletId);
      return new VerifyResult.No(Rejected.EXPIRED);
    }
    boolean sameNonce =
        MessageDigest.isEqual(
            stored.get().nonce().getBytes(StandardCharsets.UTF_8),
            nonce.getBytes(StandardCharsets.UTF_8));
    // A mensagem verificada é a guardada no servidor, nunca uma enviada pelo cliente.
    if (!sameNonce
        || !PossessionVerifier.verify(
            wallet.get().network(), wallet.get().address(), stored.get().message(), signature)) {
      log.warn("posse: assinatura recusada usuario_id={} carteira_id={}", userId, walletId);
      return new VerifyResult.No(Rejected.INVALID_SIGNATURE);
    }
    if (!queries.markVerified(userId, walletId, now)) {
      return new VerifyResult.No(Rejected.NOT_FOUND);
    }
    log.info("posse: carteira verificada usuario_id={} carteira_id={}", userId, walletId);
    return walletService
        .find(userId, walletId)
        .<VerifyResult>map(VerifyResult.Ok::new)
        .orElseGet(() -> new VerifyResult.No(Rejected.NOT_FOUND));
  }

  private String newNonce() {
    byte[] bytes = new byte[NONCE_BYTES];
    random.nextBytes(bytes);
    return HexFormat.of().formatHex(bytes);
  }
}
