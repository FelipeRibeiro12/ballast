package com.felipe.ballast.wallets;

import com.felipe.ballast.limits.LimitReachedException;
import com.felipe.ballast.limits.UserLimit;
import com.felipe.ballast.limits.UserLimits;
import com.felipe.ballast.web.Constraints;
import com.felipe.ballast.web.WriteTransaction;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

// O endereço nunca entra em log, mensagem de exceção nem métrica (ADR-071): o dado sensível é a
// ligação entre a pessoa e o endereço. Os logs daqui levam só os ids.
@Service
class WalletService {

  private static final Logger log = LoggerFactory.getLogger(WalletService.class);

  static final Duration MIN_INTERVAL = Duration.ofSeconds(60);
  private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
  private static final int MAX_SYMBOL = 20;
  private static final int MAX_INTEGER_DIGITS = 20;
  private static final int MAX_DECIMALS = 18;
  private static final int MINT_PREFIX = 8;
  private static final Pattern EVM_CONTRACT = Pattern.compile("0x[0-9a-fA-F]{40}");
  private static final String ADDRESS_UNIQUE = "carteira_observada_usuario_rede_endereco_unico";

  record View(WalletQueries.Wallet wallet, List<WalletQueries.Balance> balances) {}

  sealed interface CreateResult {
    record Created(View view) implements CreateResult {}

    enum Rejected implements CreateResult {
      ACCOUNT_NOT_FOUND,
      NOT_CRYPTO,
      ACCOUNT_ARCHIVED,
      INVALID_ADDRESS,
      DUPLICATE
    }
  }

  sealed interface SyncResult {
    record Done(View view) implements SyncResult {}

    enum Rejected implements SyncResult {
      NOT_FOUND,
      ACCOUNT_ARCHIVED
    }
  }

  private final WalletQueries queries;
  private final WalletIndexer indexer;
  private final WriteTransaction transaction;
  private final UserLimits limits;
  private final Clock clock;

  WalletService(
      WalletQueries queries,
      WalletIndexer indexer,
      WriteTransaction transaction,
      UserLimits limits,
      Clock clock) {
    this.queries = queries;
    this.indexer = indexer;
    this.transaction = transaction;
    this.limits = limits;
    this.clock = clock;
  }

  // accountId chega nulo quando o texto do cliente não era UUID. A conta é travada como nas
  // escritas de movimento, e o unique da V36 segura a corrida de dois cadastros iguais.
  CreateResult create(
      UUID userId, UUID accountId, WalletNetwork network, String address, String label) {
    Optional<String> normalized = WalletAddress.normalize(network, address);
    if (normalized.isEmpty()) {
      return CreateResult.Rejected.INVALID_ADDRESS;
    }
    try {
      return transaction.execute(
          status -> {
            if (accountId == null) {
              return CreateResult.Rejected.ACCOUNT_NOT_FOUND;
            }
            Optional<WalletQueries.LockedAccount> account = queries.lockAccount(userId, accountId);
            if (account.isEmpty()) {
              return CreateResult.Rejected.ACCOUNT_NOT_FOUND;
            }
            if (!account.get().crypto()) {
              return CreateResult.Rejected.NOT_CRYPTO;
            }
            if (account.get().archived()) {
              return CreateResult.Rejected.ACCOUNT_ARCHIVED;
            }
            limits.checkRoom(userId, UserLimit.WALLETS);
            WalletQueries.Wallet saved =
                queries.insertWallet(userId, accountId, network, normalized.get(), label);
            return new CreateResult.Created(new View(saved, List.of()));
          });
    } catch (DataIntegrityViolationException e) {
      if (Constraints.isViolated(e, ADDRESS_UNIQUE)) {
        return CreateResult.Rejected.DUPLICATE;
      }
      throw e;
    }
  }

  Optional<View> find(UUID userId, UUID id) {
    return queries.findWallet(userId, id).map(wallet -> view(userId, wallet));
  }

  List<View> list(UUID userId) {
    return queries.listWallets(userId).stream().map(wallet -> view(userId, wallet)).toList();
  }

  // Os movimentos sincronizados saem em cascata (V36). A conta é travada antes, na ordem de todo
  // mundo: conta, depois carteira.
  boolean delete(UUID userId, UUID id) {
    return transaction.execute(
        status -> {
          Optional<WalletQueries.Wallet> wallet = queries.findWallet(userId, id);
          if (wallet.isEmpty()) {
            return false;
          }
          queries.lockAccount(userId, wallet.get().accountId());
          return queries.deleteWallet(userId, id) > 0;
        });
  }

  // O indexador é chamado fora da transação: a trava da conta não espera a rede. A tentativa fica
  // gravada antes da chamada, então duas requisições juntas leem a rede uma vez só.
  SyncResult sync(UUID userId, UUID walletId) {
    Claim claim = transaction.execute(status -> claim(userId, walletId));
    return switch (claim) {
      case Claim.Missing missing -> SyncResult.Rejected.NOT_FOUND;
      case Claim.Archived archived -> SyncResult.Rejected.ACCOUNT_ARCHIVED;
      case Claim.TooSoon tooSoon -> new SyncResult.Done(view(userId, tooSoon.wallet()));
      case Claim.Granted granted -> read(userId, granted.wallet());
    };
  }

  private SyncResult read(UUID userId, WalletQueries.Wallet wallet) {
    WalletIndexer.Reading reading;
    try {
      reading = indexer.read(wallet.network(), wallet.address());
    } catch (IndexerUnavailableException e) {
      return failed(userId, wallet, e.reason().code());
    } catch (RuntimeException e) {
      // Só a classe: a mensagem de uma exceção qualquer pode trazer o corpo da requisição.
      log.warn("leitura da carteira falhou: carteira_id={} tipo={}", wallet.id(), type(e));
      return failed(userId, wallet, IndexerUnavailableException.Reason.UNAVAILABLE.code());
    }
    return transaction.execute(status -> apply(userId, wallet.id(), reading));
  }

  private SyncResult failed(UUID userId, WalletQueries.Wallet wallet, String code) {
    log.warn("leitura da carteira recusada: carteira_id={} codigo={}", wallet.id(), code);
    transaction.execute(
        status -> {
          queries.markFailure(userId, wallet.id(), code);
          return null;
        });
    return new SyncResult.Done(find(userId, wallet.id()).orElseThrow());
  }

  private sealed interface Claim {
    record Missing() implements Claim {}

    record Archived() implements Claim {}

    record TooSoon(WalletQueries.Wallet wallet) implements Claim {}

    record Granted(WalletQueries.Wallet wallet) implements Claim {}
  }

  private Claim claim(UUID userId, UUID walletId) {
    Optional<WalletQueries.Wallet> seen = queries.findWallet(userId, walletId);
    if (seen.isEmpty()) {
      return new Claim.Missing();
    }
    Optional<WalletQueries.LockedAccount> account =
        queries.lockAccount(userId, seen.get().accountId());
    if (account.isEmpty()) {
      return new Claim.Missing();
    }
    if (account.get().archived()) {
      return new Claim.Archived();
    }
    Optional<WalletQueries.Wallet> wallet = queries.lockWallet(userId, walletId);
    if (wallet.isEmpty()) {
      return new Claim.Missing();
    }
    Instant now = clock.instant();
    Instant last = wallet.get().attemptedAt();
    if (last != null
        && !last.isAfter(now)
        && Duration.between(last, now).compareTo(MIN_INTERVAL) < 0) {
      return new Claim.TooSoon(wallet.get());
    }
    queries.markAttempt(userId, walletId, now);
    return new Claim.Granted(wallet.get());
  }

  private SyncResult apply(UUID userId, UUID walletId, WalletIndexer.Reading reading) {
    Optional<WalletQueries.Wallet> seen = queries.findWallet(userId, walletId);
    if (seen.isEmpty()) {
      return SyncResult.Rejected.NOT_FOUND;
    }
    UUID accountId = seen.get().accountId();
    // A conta some ou é arquivada entre o claim e aqui: nada é gravado.
    Optional<WalletQueries.LockedAccount> account = queries.lockAccount(userId, accountId);
    if (account.isEmpty()) {
      return SyncResult.Rejected.NOT_FOUND;
    }
    if (account.get().archived()) {
      return SyncResult.Rejected.ACCOUNT_ARCHIVED;
    }
    Optional<WalletQueries.Wallet> wallet = queries.lockWallet(userId, walletId);
    if (wallet.isEmpty()) {
      return SyncResult.Rejected.NOT_FOUND;
    }
    Diff diff = new Diff(userId, wallet.get(), reading);
    diff.run();
    queries.markSuccess(userId, walletId, clock.instant());
    log.info(
        "carteira sincronizada: carteira_id={} movimentos={} ignorados={}",
        walletId,
        diff.written,
        diff.ignored);
    return new SyncResult.Done(find(userId, walletId).orElseThrow());
  }

  // Compara a leitura com os movimentos sincronizados da carteira e grava a diferença por ativo.
  private final class Diff {
    private final UUID userId;
    private final WalletQueries.Wallet wallet;
    private final WalletIndexer.Reading reading;
    private int written;
    private int ignored;
    private boolean partial;
    private boolean roomChecked;

    Diff(UUID userId, WalletQueries.Wallet wallet, WalletIndexer.Reading reading) {
      this.userId = userId;
      this.wallet = wallet;
      this.reading = reading;
      this.partial = reading.partial();
    }

    void run() {
      Map<UUID, BigDecimal> current = queries.syncedTotals(userId, wallet.id());
      LocalDate today = clock.instant().atZone(ZONE).toLocalDate();
      Set<UUID> seen = new HashSet<>();
      for (WalletIndexer.Holding holding : clean()) {
        Optional<UUID> assetId = resolveAsset(holding);
        if (assetId.isEmpty()) {
          ignored++;
          continue;
        }
        seen.add(assetId.get());
        BigDecimal delta =
            holding.quantity().subtract(current.getOrDefault(assetId.get(), BigDecimal.ZERO));
        write(assetId.get(), delta, today);
      }
      // O que sumiu da leitura zera. Leitura parcial não prova ausência: o ativo pode só não ter
      // cabido no teto do indexador.
      if (!partial) {
        current.forEach(
            (assetId, quantity) -> {
              if (!seen.contains(assetId)) {
                write(assetId, quantity.negate(), today);
              }
            });
      }
    }

    private void write(UUID assetId, BigDecimal delta, LocalDate today) {
      if (delta.signum() == 0) {
        return;
      }
      if (!roomChecked) {
        // Um teto só para a leitura inteira: confere a primeira escrita. Passar um pouco do teto
        // numa leitura é melhor que parar no meio de uma carteira.
        limits.checkRoom(userId, UserLimit.CRYPTO_MOVEMENTS);
        roomChecked = true;
      }
      queries.insertSyncedMovement(
          userId,
          wallet.accountId(),
          wallet.id(),
          assetId,
          delta.signum() > 0 ? "entrada" : "saida",
          delta.abs(),
          today);
      written++;
    }

    // Resposta do indexador é dado de terceiro (docs/ameacas.md): o que não passa na validação
    // fica de fora e torna a leitura parcial, porque o ativo não foi visto.
    private Collection<WalletIndexer.Holding> clean() {
      Map<String, WalletIndexer.Holding> byContract = new LinkedHashMap<>();
      for (WalletIndexer.Holding raw : reading.holdings()) {
        Optional<WalletIndexer.Holding> valid = validate(raw);
        if (valid.isEmpty()) {
          ignored++;
          partial = true;
          continue;
        }
        WalletIndexer.Holding holding = valid.get();
        byContract.merge(
            String.valueOf(holding.contract()),
            holding,
            (a, b) ->
                new WalletIndexer.Holding(
                    a.contract(), a.symbol(), a.decimals(), a.quantity().add(b.quantity())));
      }
      return byContract.values();
    }

    private Optional<WalletIndexer.Holding> validate(WalletIndexer.Holding raw) {
      if (raw == null || raw.quantity() == null || raw.quantity().signum() < 0) {
        return Optional.empty();
      }
      String contract = raw.contract();
      if (contract != null) {
        if (wallet.network().isEvm()) {
          if (!EVM_CONTRACT.matcher(contract).matches()) {
            return Optional.empty();
          }
          contract = contract.toLowerCase(Locale.ROOT);
        } else if (WalletAddress.normalize(wallet.network(), contract).isEmpty()) {
          return Optional.empty();
        }
      }
      int decimals = contract == null ? wallet.network().nativeDecimals() : raw.decimals();
      BigDecimal quantity = raw.quantity().stripTrailingZeros();
      if (decimals < 0
          || decimals > MAX_DECIMALS
          || quantity.scale() > decimals
          || quantity.precision() - quantity.scale() > MAX_INTEGER_DIGITS) {
        return Optional.empty();
      }
      return Optional.of(new WalletIndexer.Holding(contract, raw.symbol(), decimals, quantity));
    }

    // O ativo existente vale como está; o novo nasce não confiável, menos o nativo (ADR-071). Sem
    // vaga no teto de ativos, o novo fica de fora e a leitura segue com os que já existem.
    private Optional<UUID> resolveAsset(WalletIndexer.Holding holding) {
      Optional<WalletQueries.Asset> found =
          queries.findAsset(userId, wallet.network().asset(), holding.contract());
      if (found.isPresent()) {
        return Optional.of(found.get().id());
      }
      if (holding.quantity().signum() == 0) {
        return Optional.empty();
      }
      try {
        limits.checkRoom(userId, UserLimit.CRYPTO_ASSETS);
      } catch (LimitReachedException e) {
        return Optional.empty();
      }
      boolean isNative = holding.contract() == null;
      return Optional.of(
          queries.insertAsset(
              userId,
              wallet.network().asset(),
              holding.contract(),
              isNative ? wallet.network().nativeSymbol() : symbolOf(holding),
              isNative ? wallet.network().nativeName() : null,
              holding.decimals(),
              isNative));
    }

    // O Solana não traz metadado, então o símbolo é o começo do mint. No EVM o texto do indexador
    // é limpo e cortado; vazio cai no começo do contrato.
    private String symbolOf(WalletIndexer.Holding holding) {
      String cleaned = wallet.network().isEvm() ? sanitize(holding.symbol()) : "";
      return cleaned.isEmpty() ? holding.contract().substring(0, MINT_PREFIX) : cleaned;
    }
  }

  // Sem caracteres de controle ou de formatação (bidi, largura zero), espaços repetidos viram um e
  // as pontas saem: o CHECK do banco recusa espaço nas pontas do símbolo.
  static String sanitize(String raw) {
    if (raw == null) {
      return "";
    }
    String cleaned =
        raw.replaceAll("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]", "").replaceAll("\\s+", " ").strip();
    int[] points = cleaned.codePoints().limit(MAX_SYMBOL).toArray();
    return new String(points, 0, points.length).strip();
  }

  private View view(UUID userId, WalletQueries.Wallet wallet) {
    return new View(wallet, queries.balances(userId, wallet.id()));
  }

  // Tipo da exceção sem pacote: o nome simples basta no log.
  private static String type(Throwable e) {
    return e.getClass().getSimpleName();
  }

  // Usado pelo job: o que ler, uma carteira por vez.
  List<WalletQueries.Ref> activeRefs() {
    return new ArrayList<>(queries.activeRefs());
  }
}
