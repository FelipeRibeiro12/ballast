package com.felipe.ballast.entries;

import com.felipe.ballast.web.Constraints;
import com.felipe.ballast.web.WriteTransaction;
import java.math.BigDecimal;
import java.time.LocalDate;
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
// um PUT que troca de conta seguem a ordem de id, para trocas cruzadas não se travarem. A ordem
// geral é template, lançamento, conta (ADR-057).
@Service
class EntryService {

  private static final String PLANNED = "previsto";

  static final int OVERDUE_LIMIT = 200;

  private static final String ACCOUNT_FK = "lancamento_conta_mesmo_usuario";
  private static final String CATEGORY_FK = "lancamento_categoria_mesmo_usuario_e_tipo";
  // Gatilho da V12: a lista da conta de benefício mudou entre a conferência e o INSERT.
  private static final String PERMITTED_TRIGGER = "lancamento_categoria_permitida_no_beneficio";

  // As FKs das pernas recusam apagar ou mover lançamento com a ligação viva. A checagem de
  // isTransferLeg já barra antes; isto cobre o que passar dela.
  // FK da V11: recusa apagar a primeira parcela com as outras apontando para ela.
  private static final String PURCHASE_FK = "lancamento_parcela_mesma_compra";

  private static final String LEG_FK_OUT = "transferencia_saida_perna_valida";
  private static final String LEG_FK_IN = "transferencia_entrada_perna_valida";

  sealed interface SaveResult {
    record Saved(EntrySummary entry) implements SaveResult {}

    enum Rejected implements SaveResult {
      ENTRY_NOT_FOUND,
      ACCOUNT_NOT_FOUND,
      ACCOUNT_ARCHIVED,
      CRYPTO_ACCOUNT,
      INVESTMENT_ACCOUNT,
      CATEGORY_NOT_FOUND,
      CATEGORY_NOT_PERMITTED,
      BALANCE_OUT_OF_RANGE,
      TRANSFER_LEG,
      NOT_PLANNED,
      EDIT_WHOLE_PURCHASE,
      INSTALLMENT_NOT_EXPENSE,
      PURCHASE_OUT_OF_RANGE
    }
  }

  enum DeleteResult {
    DELETED,
    NOT_FOUND,
    BALANCE_OUT_OF_RANGE,
    TRANSFER_LEG,
    CANCEL_OCCURRENCE,
    FIRST_INSTALLMENT,
    // Só a rota de transferência devolve: o aporte de meta aponta para ela (V27).
    LINKED_TO_DEPOSIT
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

  // Confirmar ou cancelar uma ocorrência prevista. Ordem de travas: template (só em ocorrência),
  // lançamento, conta (só ao confirmar; cancelar não mexe em saldo).
  // Valor e data opcionais só chegam com REALIZED (o controller recusa com cancelado) e são
  // gravados na mesma transação da confirmação, antes de conferir o teto com o valor novo.
  SaveResult changeStatus(
      UUID userId, UUID id, TargetStatus target, BigDecimal amount, LocalDate date) {
    return transaction.execute(
        status -> changeStatusLocked(status, userId, id, target, amount, date));
  }

  // Um item a mais que o limite diz se houve corte; ele não sai na resposta.
  record Overdue(List<EntrySummary> entries, boolean more) {}

  Overdue listOverdue(UUID userId, LocalDate today) {
    List<EntrySummary> found = queries.listOverdue(userId, today, OVERDUE_LIMIT + 1);
    boolean more = found.size() > OVERDUE_LIMIT;
    return new Overdue(more ? found.subList(0, OVERDUE_LIMIT) : found, more);
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
      if (isConstraint(e, PURCHASE_FK)) {
        return DeleteResult.FIRST_INSTALLMENT;
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
    UUID id =
        entries
            .saveAndFlush(
                new Entry(userId, input, cashDate(userId, input.accountId(), input.date())))
            .getId();
    if (!balanceFits(userId, input.accountId())) {
      status.setRollbackOnly();
      return SaveResult.Rejected.BALANCE_OUT_OF_RANGE;
    }
    return saved(userId, id);
  }

  // Ordem de travas (ADR-057): template, lançamento, conta. A ocorrência precisa do template em
  // modo compartilhado (a marca de apagada e a FK pedem), e pedir depois do lançamento fecha ciclo
  // com quem edita o template, que trava o template e depois as linhas dele.
  private Optional<EntryQueries.LockedEntry> lockEntryInOrder(UUID userId, UUID id) {
    queries
        .templateOf(userId, id)
        .ifPresent(template -> queries.lockTemplateShared(userId, template));
    return queries.lockEntry(userId, id);
  }

  private SaveResult changeStatusLocked(
      TransactionStatus status,
      UUID userId,
      UUID id,
      TargetStatus target,
      BigDecimal amount,
      LocalDate date) {
    Optional<EntryQueries.LockedEntry> entry = lockEntryInOrder(userId, id);
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
    // Com data nova em conta de cartão, a data vira a competência e a caixa é o vencimento dela.
    LocalDate cashDate = date == null ? null : cashDate(userId, entry.get().accountId(), date);
    if (queries.changeStatus(userId, id, target, amount, date, cashDate) == 0) {
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
    Optional<EntryQueries.LockedEntry> locked = lockEntryInOrder(userId, id);
    if (locked.isEmpty()) {
      return SaveResult.Rejected.ENTRY_NOT_FOUND;
    }
    Optional<UUID> currentAccount = locked.map(EntryQueries.LockedEntry::accountId);
    // Antes de travar qualquer conta: a perna já está travada, então o DELETE da transferência
    // espera aqui e o par nunca fica pela metade.
    if (queries.isTransferLeg(userId, id)) {
      return SaveResult.Rejected.TRANSFER_LEG;
    }
    if (input.accountId() == null) {
      return SaveResult.Rejected.ACCOUNT_NOT_FOUND;
    }
    boolean installment = locked.get().purchaseId() != null;
    if (installment) {
      Optional<SaveResult.Rejected> refused = checkInstallmentEdit(locked.get(), input);
      if (refused.isPresent()) {
        return refused.get();
      }
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
            input.date(),
            installment
                ? locked.get().cashDate()
                : cashDate(userId, input.accountId(), input.date()));
    if (changed == 0) {
      return SaveResult.Rejected.ENTRY_NOT_FOUND;
    }
    queries.markDetached(userId, id);
    if (!balanceFits(userId, currentAccount.get()) || !balanceFits(userId, input.accountId())) {
      status.setRollbackOnly();
      return SaveResult.Rejected.BALANCE_OUT_OF_RANGE;
    }
    // O saldo do cartão pode ficar na faixa com a soma da compra fora dela (saldo inicial alto).
    // Recusar aqui evita gravar um estado que GET /compras-parceladas não consegue mostrar.
    if (installment && !queries.purchaseFits(userId, locked.get().purchaseId())) {
      status.setRollbackOnly();
      return SaveResult.Rejected.PURCHASE_OUT_OF_RANGE;
    }
    return saved(userId, id);
  }

  private DeleteResult deleteLocked(TransactionStatus status, UUID userId, UUID id) {
    Optional<EntryQueries.LockedEntry> entry = lockEntryInOrder(userId, id);
    if (entry.isEmpty()) {
      return DeleteResult.NOT_FOUND;
    }
    if (queries.isTransferLeg(userId, id)) {
      return DeleteResult.TRANSFER_LEG;
    }
    // As outras parcelas apontam para a primeira: ela só sai com a compra inteira.
    if (id.equals(entry.get().purchaseId())) {
      return DeleteResult.FIRST_INSTALLMENT;
    }
    // Apagar uma prevista liberaria a chave (template, data) e a regra geraria a mesma ocorrência
    // de novo. Cancelar guarda a linha e a chave. Realizada e cancelada podem sair; a cancelada
    // deixa uma marca em ocorrencia_apagada e nunca mais volta (ADR-054).
    if (entry.get().templateId() != null && PLANNED.equals(entry.get().status())) {
      return DeleteResult.CANCEL_OCCURRENCE;
    }
    UUID account = entry.get().accountId();
    queries.lockAccount(userId, account);
    queries.markCancelledOccurrenceDeleted(userId, id);
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

  record Page(List<EntrySummary> items, boolean more) {}

  // Busca um a mais que o limite só para saber se há próxima página.
  Page list(
      UUID userId, LocalDate from, LocalDate to, UUID accountId, EntryCursor after, int limit) {
    List<EntrySummary> found = queries.list(userId, from, to, accountId, after, limit + 1);
    boolean more = found.size() > limit;
    return new Page(more ? found.subList(0, limit) : found, more);
  }

  // data_caixa do lançamento: a própria data fora de cartão, o vencimento da fatura em cartão.
  private LocalDate cashDate(UUID userId, UUID accountId, LocalDate date) {
    return queries.findCardBilling(userId, accountId).map(card -> card.dueDate(date)).orElse(date);
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
    if (queries.isCryptoAccount(userId, input.accountId())) {
      return Optional.of(SaveResult.Rejected.CRYPTO_ACCOUNT);
    }
    if (queries.isInvestmentAccount(userId, input.accountId())) {
      return Optional.of(SaveResult.Rejected.INVESTMENT_ACCOUNT);
    }
    if (accountArchived.get() && !sameAccount) {
      return Optional.of(SaveResult.Rejected.ACCOUNT_ARCHIVED);
    }
    if (input.categoryId() == null
        || !queries.categoryExists(userId, input.categoryId(), input.type())) {
      return Optional.of(SaveResult.Rejected.CATEGORY_NOT_FOUND);
    }
    // Só despesa é restrita. A conta já está travada, então a lista lida aqui é a que vale.
    if (input.type() == EntryType.EXPENSE
        && !queries.isCategoryPermitted(userId, input.accountId(), input.categoryId())) {
      return Optional.of(SaveResult.Rejected.CATEGORY_NOT_PERMITTED);
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
    if (Constraints.isViolated(error, PERMITTED_TRIGGER)) {
      return Optional.of(SaveResult.Rejected.CATEGORY_NOT_PERMITTED);
    }
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

  // A parcela não troca de conta, data, título nem categoria (a data enviada é a da compra, a
  // competência gravada) e continua despesa. Título e categoria só mudam na compra inteira
  // (ADR-054). A caixa dela é do cálculo da compra e não se recalcula aqui.
  private static Optional<SaveResult.Rejected> checkInstallmentEdit(
      EntryQueries.LockedEntry current, EntryInput input) {
    if (!current.accountId().equals(input.accountId())
        || input.date().compareTo(current.competenceDate()) != 0
        || !current.title().equals(input.title())
        || !current.categoryId().equals(input.categoryId())) {
      return Optional.of(SaveResult.Rejected.EDIT_WHOLE_PURCHASE);
    }
    if (input.type() != EntryType.EXPENSE) {
      return Optional.of(SaveResult.Rejected.INSTALLMENT_NOT_EXPENSE);
    }
    return Optional.empty();
  }

  private static boolean isConstraint(Throwable error, String name) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof ConstraintViolationException violation
          && name.equals(violation.getConstraintName())) {
        return true;
      }
    }
    return false;
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
