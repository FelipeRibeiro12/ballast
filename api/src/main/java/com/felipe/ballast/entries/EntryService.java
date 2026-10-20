package com.felipe.ballast.entries;

import com.felipe.ballast.web.WriteTransaction;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionStatus;

// Assume entrada já validada e normalizada na borda. Todo método recebe o dono vindo da sessão.
//
// Toda escrita roda numa transação que trava a linha da conta (SELECT ... FOR UPDATE) antes de
// conferir conta arquivada e saldo. Arquivar faz UPDATE na mesma linha e entra na fila, e dois
// lançamentos simultâneos na mesma conta não leem o mesmo saldo (ADR-024). Os dois travamentos de
// um PUT que troca de conta seguem a ordem de id, para trocas cruzadas não se travarem.
@Service
class EntryService {

  private static final String PLANNED = "previsto";

  private static final String ACCOUNT_FK = "lancamento_conta_mesmo_usuario";
  private static final String CATEGORY_FK = "lancamento_categoria_mesmo_usuario_e_tipo";

  // As FKs das pernas recusam apagar ou mover lançamento com a ligação viva. A checagem de
  // isTransferLeg já barra antes; isto cobre o que passar dela.
  private static final String LEG_FK_OUT = "transferencia_saida_perna_valida";
  private static final String LEG_FK_IN = "transferencia_entrada_perna_valida";

  sealed interface SaveResult {
    record Saved(EntrySummary entry) implements SaveResult {}

    enum Rejected implements SaveResult {
      ENTRY_NOT_FOUND,
      ACCOUNT_NOT_FOUND,
      ACCOUNT_ARCHIVED,
      CATEGORY_NOT_FOUND,
      BALANCE_OUT_OF_RANGE,
      TRANSFER_LEG,
      NOT_PLANNED
    }
  }

  enum DeleteResult {
    DELETED,
    NOT_FOUND,
    BALANCE_OUT_OF_RANGE,
    TRANSFER_LEG,
    CANCEL_OCCURRENCE
  }

  private final EntryRepository entries;
  private final EntryQueries queries;
  private final WriteTransaction transaction;

  EntryService(EntryRepository entries, EntryQueries queries, WriteTransaction transaction) {
    this.entries = entries;
    this.queries = queries;
    this.transaction = transaction;
  }

  // A violação da FK composta sai da transação já com rollback e é capturada aqui fora: capturar
  // dentro deixaria a transação marcada como rollback-only e o commit lançaria
  // UnexpectedRollbackException. A conferência de conta e categoria dentro é só para a mensagem;
  // quem segura a corrida de dono é a FK.
  SaveResult create(UUID userId, EntryInput input) {
    try {
      return transaction.execute(status -> createLocked(status, userId, input));
    } catch (DataIntegrityViolationException e) {
      return rejectionOf(e).orElseThrow(() -> e);
    }
  }

  // Trocar de conta exige conta ativa. Ficar na mesma conta vale mesmo arquivada, senão um
  // lançamento antigo não poderia ser corrigido depois de arquivar a conta.
  SaveResult update(UUID userId, UUID id, EntryInput input) {
    try {
      return transaction.execute(status -> updateLocked(status, userId, id, input));
    } catch (DataIntegrityViolationException e) {
      return rejectionOf(e).orElseThrow(() -> e);
    }
  }

  // Confirmar ou cancelar uma ocorrência prevista. Ordem de travas: o lançamento, depois a conta
  // (só ao confirmar; cancelar não mexe em saldo). Nenhum caminho trava template aqui, então não
  // há ciclo com o job (template, depois conta) nem com desativar (template, depois a linha).
  SaveResult changeStatus(UUID userId, UUID id, TargetStatus target) {
    return transaction.execute(status -> changeStatusLocked(status, userId, id, target));
  }

  // NOT_FOUND para lançamento de outro usuário ou inexistente, sem distinguir. Apagar uma
  // despesa grande também pode tirar o saldo da faixa, por isso confere como as outras escritas.
  DeleteResult delete(UUID userId, UUID id) {
    try {
      return transaction.execute(status -> deleteLocked(status, userId, id));
    } catch (DataIntegrityViolationException e) {
      if (isLegViolation(e)) {
        return DeleteResult.TRANSFER_LEG;
      }
      throw e;
    }
  }

  private SaveResult createLocked(TransactionStatus status, UUID userId, EntryInput input) {
    if (input.accountId() == null) {
      return SaveResult.Rejected.ACCOUNT_NOT_FOUND;
    }
    Optional<Boolean> archived = queries.lockAccount(userId, input.accountId());
    Optional<SaveResult.Rejected> rejected = checkReferences(userId, input, archived, false);
    if (rejected.isPresent()) {
      return rejected.get();
    }
    UUID id = entries.saveAndFlush(new Entry(userId, input)).getId();
    if (!balanceFits(userId, input.accountId())) {
      status.setRollbackOnly();
      return SaveResult.Rejected.BALANCE_OUT_OF_RANGE;
    }
    return saved(userId, id);
  }

  private SaveResult changeStatusLocked(
      TransactionStatus status, UUID userId, UUID id, TargetStatus target) {
    Optional<EntryQueries.LockedEntry> entry = queries.lockEntry(userId, id);
    if (entry.isEmpty()) {
      return SaveResult.Rejected.ENTRY_NOT_FOUND;
    }
    // Antes do status: a perna é realizada, e a resposta certa para ela é a rota de transferência.
    if (queries.isTransferLeg(userId, id)) {
      return SaveResult.Rejected.TRANSFER_LEG;
    }
    if (!PLANNED.equals(entry.get().status())) {
      return SaveResult.Rejected.NOT_PLANNED;
    }
    if (target == TargetStatus.REALIZED) {
      Optional<Boolean> archived = queries.lockAccount(userId, entry.get().accountId());
      if (archived.isEmpty()) {
        return SaveResult.Rejected.ENTRY_NOT_FOUND;
      }
      if (archived.get()) {
        return SaveResult.Rejected.ACCOUNT_ARCHIVED;
      }
    }
    if (queries.changeStatus(userId, id, target) == 0) {
      return SaveResult.Rejected.ENTRY_NOT_FOUND;
    }
    if (target == TargetStatus.REALIZED && !balanceFits(userId, entry.get().accountId())) {
      status.setRollbackOnly();
      return SaveResult.Rejected.BALANCE_OUT_OF_RANGE;
    }
    return saved(userId, id);
  }

  private SaveResult updateLocked(
      TransactionStatus status, UUID userId, UUID id, EntryInput input) {
    // O lançamento é travado primeiro para a conta de origem não mudar entre a leitura e o
    // travamento dela. Nenhum outro caminho trava lançamento depois de travar conta.
    Optional<UUID> currentAccount = queries.lockEntry(userId, id).map(e -> e.accountId());
    if (currentAccount.isEmpty()) {
      return SaveResult.Rejected.ENTRY_NOT_FOUND;
    }
    // Antes de travar qualquer conta: a perna já está travada, então o DELETE da transferência
    // espera aqui e o par nunca fica pela metade.
    if (queries.isTransferLeg(userId, id)) {
      return SaveResult.Rejected.TRANSFER_LEG;
    }
    if (input.accountId() == null) {
      return SaveResult.Rejected.ACCOUNT_NOT_FOUND;
    }
    boolean sameAccount = currentAccount.get().equals(input.accountId());
    Optional<Boolean> targetArchived = Optional.empty();
    for (UUID account : lockOrder(currentAccount.get(), input.accountId())) {
      Optional<Boolean> archived = queries.lockAccount(userId, account);
      if (account.equals(input.accountId())) {
        targetArchived = archived;
      }
    }
    Optional<SaveResult.Rejected> rejected =
        checkReferences(userId, input, targetArchived, sameAccount);
    if (rejected.isPresent()) {
      return rejected.get();
    }
    int changed =
        entries.replace(
            userId,
            id,
            input.type().value(),
            input.title(),
            input.description(),
            input.categoryId(),
            input.accountId(),
            input.amount(),
            input.date());
    if (changed == 0) {
      return SaveResult.Rejected.ENTRY_NOT_FOUND;
    }
    queries.markDetached(userId, id);
    if (!balanceFits(userId, currentAccount.get()) || !balanceFits(userId, input.accountId())) {
      status.setRollbackOnly();
      return SaveResult.Rejected.BALANCE_OUT_OF_RANGE;
    }
    return saved(userId, id);
  }

  private DeleteResult deleteLocked(TransactionStatus status, UUID userId, UUID id) {
    Optional<EntryQueries.LockedEntry> entry = queries.lockEntry(userId, id);
    if (entry.isEmpty()) {
      return DeleteResult.NOT_FOUND;
    }
    if (queries.isTransferLeg(userId, id)) {
      return DeleteResult.TRANSFER_LEG;
    }
    // Apagar uma prevista liberaria a chave (template, data) e a regra geraria a mesma ocorrência
    // de novo. Cancelar guarda a linha e a chave. Realizada e cancelada podem sair.
    if (entry.get().templateId() != null && PLANNED.equals(entry.get().status())) {
      return DeleteResult.CANCEL_OCCURRENCE;
    }
    UUID account = entry.get().accountId();
    queries.lockAccount(userId, account);
    if (entries.deleteOwned(userId, id) == 0) {
      return DeleteResult.NOT_FOUND;
    }
    if (!balanceFits(userId, account)) {
      status.setRollbackOnly();
      return DeleteResult.BALANCE_OUT_OF_RANGE;
    }
    return DeleteResult.DELETED;
  }

  Optional<EntrySummary> find(UUID userId, UUID id) {
    return queries.find(userId, id);
  }

  List<EntrySummary> list(
      UUID userId, java.time.LocalDate from, java.time.LocalDate to, UUID accountId) {
    return queries.list(userId, from, to, accountId);
  }

  private SaveResult saved(UUID userId, UUID id) {
    return queries
        .find(userId, id)
        .<SaveResult>map(SaveResult.Saved::new)
        .orElse(SaveResult.Rejected.ENTRY_NOT_FOUND);
  }

  private Optional<SaveResult.Rejected> checkReferences(
      UUID userId, EntryInput input, Optional<Boolean> accountArchived, boolean sameAccount) {
    if (accountArchived.isEmpty()) {
      return Optional.of(SaveResult.Rejected.ACCOUNT_NOT_FOUND);
    }
    if (accountArchived.get() && !sameAccount) {
      return Optional.of(SaveResult.Rejected.ACCOUNT_ARCHIVED);
    }
    if (input.categoryId() == null
        || !queries.categoryExists(userId, input.categoryId(), input.type())) {
      return Optional.of(SaveResult.Rejected.CATEGORY_NOT_FOUND);
    }
    return Optional.empty();
  }

  private boolean balanceFits(UUID userId, UUID accountId) {
    return queries.balanceFits(userId, accountId);
  }

  static List<UUID> lockOrder(UUID first, UUID second) {
    if (first.equals(second)) {
      return List.of(first);
    }
    return first.compareTo(second) < 0 ? List.of(first, second) : List.of(second, first);
  }

  // A FK composta é a rede de segurança: conta ou categoria de outro usuário (ou categoria de
  // outro tipo) que passou pela conferência acima ainda é recusada pelo banco. O detalhe do
  // Postgres não é logado.
  private static Optional<SaveResult.Rejected> rejectionOf(Throwable error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof ConstraintViolationException violation) {
        if (ACCOUNT_FK.equals(violation.getConstraintName())) {
          return Optional.of(SaveResult.Rejected.ACCOUNT_NOT_FOUND);
        }
        if (CATEGORY_FK.equals(violation.getConstraintName())) {
          return Optional.of(SaveResult.Rejected.CATEGORY_NOT_FOUND);
        }
        if (isLegConstraint(violation.getConstraintName())) {
          return Optional.of(SaveResult.Rejected.TRANSFER_LEG);
        }
      }
    }
    return Optional.empty();
  }

  private static boolean isLegViolation(Throwable error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof ConstraintViolationException violation
          && isLegConstraint(violation.getConstraintName())) {
        return true;
      }
    }
    return false;
  }

  private static boolean isLegConstraint(String name) {
    return LEG_FK_OUT.equals(name) || LEG_FK_IN.equals(name);
  }
}
