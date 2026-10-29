package com.felipe.ballast.imports;

import com.felipe.ballast.entries.ImportedEntries;
import com.felipe.ballast.limits.UserLimit;
import com.felipe.ballast.limits.UserLimits;
import com.felipe.ballast.web.Constraints;
import com.felipe.ballast.web.WriteTransaction;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionStatus;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

// Nada vira lançamento aqui: o envio só grava o que o arquivo trouxe e a proposta por linha. O
// arquivo em si não é guardado (ADR-069). Todo método recebe o dono vindo da sessão.
@Service
class ImportService {

  private static final String FILE_HASH_UNIQUE = "importacao_usuario_hash_unico";
  private static final String ACCOUNT_FK = "importacao_conta_do_usuario";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  enum Failure {
    NOT_FOUND,
    ACCOUNT_NOT_FOUND,
    ACCOUNT_ARCHIVED,
    ACCOUNT_NOT_ACCEPTED,
    INVALID_FILE,
    DUPLICATE_FILE,
    ALREADY_CONFIRMED,
    LINE_NOT_FOUND,
    ENTRY_NOT_FOUND,
    CATEGORY_NOT_FOUND,
    CATEGORY_TYPE_MISMATCH,
    LINE_NOT_EDITABLE,
    ENTRY_REFUSED,
    CONFIRMATION_RUNNING
  }

  sealed interface Result<T> {
    record Ok<T>(T value) implements Result<T> {}

    // importId só vem em DUPLICATE_FILE; message só em INVALID_FILE (texto fixo do leitor).
    record Failed<T>(Failure failure, UUID importId, String message) implements Result<T> {}
  }

  // action é importar, ignorar ou conciliar; os outros campos já vieram conferidos do controller.
  record LineDecision(String action, UUID entryId, UUID categoryId, String title) {}

  record Confirmation(UUID importId, int created, int reconciled, int ignored, int duplicated) {}

  record Detail(ImportQueries.ImportRow header, List<ImportQueries.LineRow> lines) {}

  private final ImportQueries queries;
  private final WriteTransaction transaction;
  private final UserLimits limits;
  private final ImportedEntries entries;

  ImportService(
      ImportQueries queries,
      WriteTransaction transaction,
      UserLimits limits,
      ImportedEntries entries) {
    this.queries = queries;
    this.transaction = transaction;
    this.limits = limits;
    this.entries = entries;
  }

  Result<Detail> upload(UUID userId, UUID accountId, byte[] content) {
    Optional<ImportQueries.AccountInfo> account = queries.findAccount(userId, accountId);
    if (account.isEmpty()) {
      return failed(Failure.ACCOUNT_NOT_FOUND);
    }
    if ("cripto".equals(account.get().type()) || "investimento".equals(account.get().type())) {
      return failed(Failure.ACCOUNT_NOT_ACCEPTED);
    }
    if (account.get().archived()) {
      return failed(Failure.ACCOUNT_ARCHIVED);
    }
    if (content == null || content.length == 0) {
      return new Result.Failed<>(Failure.INVALID_FILE, null, "arquivo vazio");
    }
    String fileHash = ImportPlanner.fileHash(content);
    Optional<UUID> existing = queries.findIdByFileHash(userId, fileHash);
    if (existing.isPresent()) {
      return new Result.Failed<>(Failure.DUPLICATE_FILE, existing.get(), null);
    }
    // Antes do parse e do plano: com o teto cheio o envio não gasta CPU nem memória. A conferência
    // com trava continua dentro da transação.
    limits.checkRoomEarly(userId, UserLimit.PENDING_IMPORTS);
    OfxFile file;
    try {
      file = OfxParser.parse(content);
    } catch (OfxException e) {
      return new Result.Failed<>(Failure.INVALID_FILE, null, e.getMessage());
    }
    List<ImportPlanner.PlannedLine> planned = plan(userId, accountId, file);
    UUID importId = queries.newId();
    try {
      transaction.execute(
          status -> {
            limits.checkRoom(userId, UserLimit.PENDING_IMPORTS);
            queries.insertImport(
                importId,
                userId,
                accountId,
                fileHash,
                planned.size(),
                (int)
                    planned.stream().filter(l -> l.transaction().invalidReason() != null).count());
            queries.insertLines(
                userId,
                importId,
                planned,
                planned.stream().map(line -> rawJson(line.transaction())).toList());
            return null;
          });
    } catch (DataIntegrityViolationException e) {
      // Dois envios do mesmo arquivo em corrida: o segundo perde no unique e recebe o id do
      // primeiro.
      if (Constraints.isViolated(e, FILE_HASH_UNIQUE)) {
        return queries
            .findIdByFileHash(userId, fileHash)
            .<Result<Detail>>map(id -> new Result.Failed<>(Failure.DUPLICATE_FILE, id, null))
            .orElseThrow(() -> e);
      }
      // A conta foi apagada entre a conferência e o INSERT.
      if (Constraints.isViolated(e, ACCOUNT_FK)) {
        return failed(Failure.ACCOUNT_NOT_FOUND);
      }
      throw e;
    }
    return new Result.Ok<>(detail(userId, importId).orElseThrow());
  }

  List<ImportQueries.ImportRow> list(UUID userId) {
    return queries.listImports(userId);
  }

  Optional<Detail> find(UUID userId, UUID id) {
    return detail(userId, id);
  }

  // Só a pendente se descarta: a confirmada é histórico de onde o lançamento veio. Descartar
  // libera o hash do arquivo para um novo envio.
  Result<Boolean> discard(UUID userId, UUID id) {
    return transaction.execute(
        status -> {
          Optional<ImportQueries.LockedImport> current = queries.lockImport(userId, id, true);
          if (current.isEmpty()) {
            return failed(Failure.NOT_FOUND);
          }
          if (!"pendente".equals(current.get().status())) {
            return failed(Failure.ALREADY_CONFIRMED);
          }
          queries.deleteImport(userId, id);
          return new Result.Ok<>(true);
        });
  }

  // Muda a decisão de uma linha. Conta, linha, lançamento e categoria são conferidos contra o
  // dono; os de outro usuário saem como não encontrados. Linha que a proposta já ignorou (inválida,
  // repetida, já importada) só aceita ignorar: importar de novo seria duplicar.
  Result<ImportQueries.LineRow> decide(
      UUID userId, UUID importId, UUID lineId, LineDecision decision) {
    return transaction.execute(
        status -> {
          Optional<ImportQueries.LockedImport> locked = queries.lockImport(userId, importId, false);
          if (locked.isEmpty()) {
            return failed(Failure.NOT_FOUND);
          }
          if (!"pendente".equals(locked.get().status())) {
            return failed(Failure.ALREADY_CONFIRMED);
          }
          Optional<ImportQueries.LineRow> line = queries.findLine(userId, importId, lineId);
          if (line.isEmpty()) {
            return failed(Failure.LINE_NOT_FOUND);
          }
          if (line.get().reason() != null && !"ignorar".equals(decision.action())) {
            return failed(Failure.LINE_NOT_EDITABLE);
          }
          if ("conciliar".equals(decision.action())
              && !queries.isReconcilable(userId, locked.get().accountId(), decision.entryId())) {
            return failed(Failure.ENTRY_NOT_FOUND);
          }
          if (decision.categoryId() != null) {
            Optional<String> type = queries.categoryType(userId, decision.categoryId());
            if (type.isEmpty()) {
              return failed(Failure.CATEGORY_NOT_FOUND);
            }
            if (!type.get().equals(line.get().type())) {
              return failed(Failure.CATEGORY_TYPE_MISMATCH);
            }
          }
          queries.updateDecision(
              userId,
              importId,
              lineId,
              decision.action(),
              decision.entryId(),
              decision.categoryId(),
              decision.title());
          return new Result.Ok<>(queries.findLine(userId, importId, lineId).orElseThrow());
        });
  }

  // Cria os lançamentos numa transação só: uma recusa de regra (conta arquivada, categoria fora
  // da lista do benefício, saldo fora da faixa) desfaz tudo e a importação continua pendente.
  // Ordem de travas do ADR-057: primeiro os lançamentos a conciliar, em ordem de id, depois a
  // conta, que cada criação trava. Quem edita um lançamento trava nessa mesma ordem.
  Result<Confirmation> confirm(UUID userId, UUID importId) {
    try {
      return transaction.execute(status -> confirmLocked(status, userId, importId));
    } catch (DataIntegrityViolationException e) {
      // Conta ou categoria apagada entre a conferência e o INSERT; a FK composta segura.
      if (Constraints.isViolated(e, "lancamento_conta_mesmo_usuario")
          || Constraints.isViolated(e, "lancamento_categoria_mesmo_usuario_e_tipo")) {
        return new Result.Failed<>(
            Failure.ENTRY_REFUSED, null, "conta ou categoria mudou durante a confirmação");
      }
      throw e;
    }
  }

  private Result<Confirmation> confirmLocked(TransactionStatus status, UUID userId, UUID importId) {
    if (!queries.tryLockConfirmations(userId)) {
      return failed(Failure.CONFIRMATION_RUNNING);
    }
    Optional<ImportQueries.LockedImport> locked = queries.lockImport(userId, importId, true);
    if (locked.isEmpty()) {
      return failed(Failure.NOT_FOUND);
    }
    if (!"pendente".equals(locked.get().status())) {
      return failed(Failure.ALREADY_CONFIRMED);
    }
    UUID accountId = locked.get().accountId();
    List<ImportQueries.LineRow> lines = queries.lines(userId, importId);
    Set<String> known =
        queries.existingExternalHashes(
            userId,
            lines.stream()
                .map(ImportQueries.LineRow::externalHash)
                .filter(Objects::nonNull)
                .toList());
    List<ImportQueries.LineRow> toReconcile = new ArrayList<>();
    List<ImportQueries.LineRow> toCreate = new ArrayList<>();
    int ignored = 0;
    int duplicated = 0;
    for (ImportQueries.LineRow line : lines) {
      String action = line.decision() != null ? line.decision() : line.proposal();
      if (line.invalidReason() != null || "ignorar".equals(action)) {
        queries.setResult(userId, line.id(), "ignorada", line.entryId());
        ignored++;
      } else if (line.externalHash() != null && known.contains(line.externalHash())) {
        queries.setResult(userId, line.id(), "duplicada", line.entryId());
        duplicated++;
      } else if ("conciliar".equals(action) && line.entryId() != null) {
        toReconcile.add(line);
      } else {
        toCreate.add(line);
      }
    }
    int reconciled = 0;
    toReconcile.sort(Comparator.comparing(ImportQueries.LineRow::entryId));
    for (ImportQueries.LineRow line : toReconcile) {
      if (queries.stampEntry(userId, accountId, line.entryId(), line.externalHash(), line.id())
          == 1) {
        queries.setResult(userId, line.id(), "conciliada", line.entryId());
        reconciled++;
      } else {
        // O lançamento sumiu ou já foi conciliado com outra linha. A transação do banco é real,
        // então vira importação em vez de sumir.
        toCreate.add(line);
      }
    }
    toCreate.sort(Comparator.comparingInt(ImportQueries.LineRow::order));
    for (ImportQueries.LineRow line : toCreate) {
      UUID categoryId = line.categoryId() != null ? line.categoryId() : line.defaultCategoryId();
      if (categoryId == null) {
        status.setRollbackOnly();
        return new Result.Failed<>(Failure.ENTRY_REFUSED, null, "categoria não encontrada");
      }
      ImportedEntries.Created created =
          entries.create(
              status,
              userId,
              new ImportedEntries.NewEntry(
                  line.type(),
                  line.title(),
                  line.description(),
                  line.amount(),
                  categoryId,
                  accountId,
                  line.date(),
                  line.externalHash(),
                  line.id()));
      if (!created.saved()) {
        status.setRollbackOnly();
        return new Result.Failed<>(Failure.ENTRY_REFUSED, null, refusal(created.failure()));
      }
      queries.setResult(userId, line.id(), "criada", created.id());
    }
    queries.markConfirmed(userId, importId);
    return new Result.Ok<>(
        new Confirmation(importId, toCreate.size(), reconciled, ignored, duplicated));
  }

  private static String refusal(ImportedEntries.Failure failure) {
    return switch (failure) {
      case ACCOUNT_NOT_FOUND -> "conta não encontrada";
      case ACCOUNT_ARCHIVED -> "conta arquivada";
      case ACCOUNT_NOT_ACCEPTED -> "conta cripto ou de investimento não recebe lançamento";
      case CATEGORY_NOT_FOUND -> "categoria não encontrada";
      case CATEGORY_NOT_PERMITTED -> "categoria não permitida nesta conta";
      case BALANCE_OUT_OF_RANGE -> "saldo da conta fora do limite";
    };
  }

  private List<ImportPlanner.PlannedLine> plan(UUID userId, UUID accountId, OfxFile file) {
    List<OfxTransaction> transactions = file.transactions();
    List<String> hashes = ImportPlanner.externalHashes(accountId, transactions);
    Set<String> alreadyImported =
        queries.existingExternalHashes(
            userId, hashes.stream().filter(Objects::nonNull).distinct().toList());
    LocalDate from = null;
    LocalDate to = null;
    for (OfxTransaction tx : transactions) {
      if (tx.date() != null) {
        from = from == null || tx.date().isBefore(from) ? tx.date() : from;
        to = to == null || tx.date().isAfter(to) ? tx.date() : to;
      }
    }
    List<ImportPlanner.Candidate> candidates =
        from == null
            ? List.of()
            : queries.candidates(
                userId,
                accountId,
                from.minusDays(ImportPlanner.RECONCILE_DAYS),
                to.plusDays(ImportPlanner.RECONCILE_DAYS));
    return ImportPlanner.plan(transactions, hashes, alreadyImported, candidates);
  }

  private Optional<Detail> detail(UUID userId, UUID id) {
    return queries.findImport(userId, id).map(row -> new Detail(row, queries.lines(userId, id)));
  }

  private static String rawJson(OfxTransaction tx) {
    try {
      return JSON.writeValueAsString(tx.raw());
    } catch (JacksonException e) {
      throw new IllegalStateException("campos do OFX não serializam", e);
    }
  }

  private static <T> Result<T> failed(Failure failure) {
    return new Result.Failed<>(failure, null, null);
  }
}
