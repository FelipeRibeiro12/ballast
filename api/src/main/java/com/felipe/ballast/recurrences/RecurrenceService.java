package com.felipe.ballast.recurrences;

import com.felipe.ballast.entries.CardBilling;
import com.felipe.ballast.entries.EntryType;
import com.felipe.ballast.web.Constraints;
import com.felipe.ballast.web.WriteTransaction;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.postgresql.util.PSQLException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionStatus;

// Assume entrada já validada e normalizada na borda. Todo método recebe o dono vindo da sessão,
// menos os do job, que recebem o dono lido do próprio template.
//
// Ordem de travas: template, só ele, no job. Desativar trava só o template; arquivar trava só a
// conta. O job trava o template, confere que continua ativo, lê "arquivada" sem travar a conta e
// insere. Travar a conta aqui fechava ciclo com PUT e confirmar (lançamento, depois conta) quando o
// INSERT esperava a linha deles. Custo aceito: numa corrida com arquivar pode sobrar prevista na
// conta arquivada, que fica fora do saldo, não confirma e pode ser cancelada (ADR-026). Criar trava
// só a conta, porque o template ainda não existe e o INSERT não colide com linha de ninguém.
// Confirmar, cancelar, editar e apagar ocorrência (EntryService) travam o lançamento e depois a
// conta, nunca o template, então não fecham ciclo com este serviço. Editar template trava o
// template, depois as previstas e só então a conta (ADR-036); reativar e apagar travam só o
// template.
@Service
class RecurrenceService {

  // "Hoje" e o horizonte seguem o dia do usuário, não o do servidor em UTC.
  static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

  // Até onde a materialização enxerga à frente, em meses contados de hoje, ponta incluída.
  static final int HORIZON_MONTHS = 12;

  // O INSERT do job ainda espera a conta no fim (a FK pede KEY SHARE, que a trava FOR UPDATE das
  // escritas bloqueia). Com 2 s, o job na frente da fila dobrava a espera da escrita do usuário.
  // O que o job pula hoje ele pega na rodada de amanhã, o horizonte tem 12 meses.
  private static final Duration JOB_LOCK_TIMEOUT = Duration.ofMillis(200);

  private static final String ACCOUNT_FK = "template_conta_mesmo_usuario";
  private static final String CATEGORY_FK = "template_categoria_mesmo_usuario_e_tipo";

  // Gatilho da V12, que o INSERT da ocorrência dispara. Na criação é a rede de segurança da
  // conferência; no job é o sinal de que a categoria saiu da lista depois que o template nasceu.
  static final String PERMITTED_TRIGGER = "lancamento_categoria_permitida_no_beneficio";

  sealed interface CreateResult {
    record Created(RecurrenceSummary recurrence, int materialized) implements CreateResult {}

    enum Rejected implements CreateResult {
      ACCOUNT_NOT_FOUND,
      ACCOUNT_ARCHIVED,
      CRYPTO_ACCOUNT,
      CATEGORY_NOT_FOUND,
      CATEGORY_NOT_PERMITTED
    }
  }

  sealed interface UpdateResult {
    record Updated(RecurrenceSummary recurrence, int materialized) implements UpdateResult {}

    enum Rejected implements UpdateResult {
      NOT_FOUND,
      TYPE_CHANGED,
      ACCOUNT_NOT_FOUND,
      ACCOUNT_ARCHIVED,
      CRYPTO_ACCOUNT,
      CATEGORY_NOT_FOUND,
      CATEGORY_NOT_PERMITTED
    }
  }

  enum ReactivateResult {
    REACTIVATED,
    NOT_FOUND,
    ACCOUNT_ARCHIVED,
    CATEGORY_NOT_PERMITTED
  }

  private final RecurrenceQueries queries;
  private final WriteTransaction transaction;
  private final Clock clock;

  RecurrenceService(RecurrenceQueries queries, WriteTransaction transaction, Clock clock) {
    this.queries = queries;
    this.transaction = transaction;
    this.clock = clock;
  }

  // Regra, template e ocorrências na mesma transação: ou o template nasce com o horizonte
  // preenchido, ou não nasce. As FKs compostas da V8 são a rede de segurança do dono; a conferência
  // abaixo existe para dar a mensagem certa.
  CreateResult create(UUID userId, RecurrenceInput input) {
    if (input.accountId() == null) {
      return CreateResult.Rejected.ACCOUNT_NOT_FOUND;
    }
    // A violação da FK composta sai da transação já com rollback e é capturada aqui fora, como no
    // EntryService. A conferência de dentro só dá a mensagem; quem segura a corrida é a FK.
    try {
      return transaction.execute(status -> createLocked(userId, input));
    } catch (DataIntegrityViolationException e) {
      return rejectionOf(e).orElseThrow(() -> e);
    }
  }

  private static Optional<CreateResult.Rejected> rejectionOf(Throwable error) {
    if (Constraints.isViolated(error, PERMITTED_TRIGGER)) {
      return Optional.of(CreateResult.Rejected.CATEGORY_NOT_PERMITTED);
    }
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof PSQLException psql && psql.getServerErrorMessage() != null) {
        String constraint = psql.getServerErrorMessage().getConstraint();
        if (ACCOUNT_FK.equals(constraint)) {
          return Optional.of(CreateResult.Rejected.ACCOUNT_NOT_FOUND);
        }
        if (CATEGORY_FK.equals(constraint)) {
          return Optional.of(CreateResult.Rejected.CATEGORY_NOT_FOUND);
        }
      }
    }
    return Optional.empty();
  }

  private CreateResult createLocked(UUID userId, RecurrenceInput input) {
    Optional<Boolean> archived = queries.lockAccount(userId, input.accountId());
    if (archived.isEmpty()) {
      return CreateResult.Rejected.ACCOUNT_NOT_FOUND;
    }
    if (queries.isCryptoAccount(userId, input.accountId())) {
      return CreateResult.Rejected.CRYPTO_ACCOUNT;
    }
    if (archived.get()) {
      return CreateResult.Rejected.ACCOUNT_ARCHIVED;
    }
    if (input.categoryId() == null
        || !queries.categoryExists(userId, input.categoryId(), input.type())) {
      return CreateResult.Rejected.CATEGORY_NOT_FOUND;
    }
    if (input.type() == EntryType.EXPENSE
        && !queries.isCategoryPermitted(userId, input.accountId(), input.categoryId())) {
      return CreateResult.Rejected.CATEGORY_NOT_PERMITTED;
    }
    UUID ruleId = queries.insertRule(userId, input.rule());
    UUID templateId = queries.insertTemplate(userId, input, ruleId);
    if (input.division() != null) {
      queries.insertDivision(userId, templateId, input.division());
    }
    LocalDate today = today();
    int materialized =
        materializeAhead(
            userId,
            templateId,
            input.accountId(),
            input.rule(),
            input.amount(),
            input.division(),
            today);
    return queries
        .find(userId, templateId, today)
        .<CreateResult>map(found -> new CreateResult.Created(found, materialized))
        .orElseThrow();
  }

  List<RecurrenceSummary> list(UUID userId) {
    return queries.list(userId, today());
  }

  Optional<RecurrenceSummary> find(UUID userId, UUID id) {
    return queries.find(userId, id, today());
  }

  // Vazio para template de outro usuário ou inexistente, sem distinguir. Repetível: desativar
  // quem já está inativo apaga o que sobrar e devolve o mesmo estado.
  Optional<RecurrenceSummary> deactivate(UUID userId, UUID id) {
    LocalDate today = today();
    boolean found =
        Boolean.TRUE.equals(
            transaction.execute(
                status -> {
                  if (queries.lockTemplate(userId, id).isEmpty()) {
                    return false;
                  }
                  queries.deactivate(userId, id);
                  queries.deleteFuturePlanned(userId, id, today);
                  return true;
                }));
    return found ? queries.find(userId, id, today) : Optional.empty();
  }

  // Ordem de travas: template, previstas (o DELETE), conta. Apagar as previstas antes de travar a
  // conta evita o ciclo com editar ocorrência, que trava o lançamento e depois a conta. Recusa que
  // aparece depois do DELETE desfaz tudo com setRollbackOnly.
  UpdateResult update(UUID userId, UUID id, RecurrenceInput input) {
    if (input.accountId() == null) {
      return queries.templateType(userId, id).isEmpty()
          ? UpdateResult.Rejected.NOT_FOUND
          : UpdateResult.Rejected.ACCOUNT_NOT_FOUND;
    }
    try {
      return transaction.execute(status -> updateLocked(status, userId, id, input));
    } catch (DataIntegrityViolationException e) {
      if (Constraints.isViolated(e, PERMITTED_TRIGGER)) {
        return UpdateResult.Rejected.CATEGORY_NOT_PERMITTED;
      }
      if (Constraints.isViolated(e, ACCOUNT_FK)) {
        return UpdateResult.Rejected.ACCOUNT_NOT_FOUND;
      }
      if (Constraints.isViolated(e, CATEGORY_FK)) {
        return UpdateResult.Rejected.CATEGORY_NOT_FOUND;
      }
      throw e;
    }
  }

  private UpdateResult updateLocked(
      TransactionStatus status, UUID userId, UUID id, RecurrenceInput input) {
    Optional<RecurrenceQueries.LockedTemplate> template = queries.lockTemplate(userId, id);
    if (template.isEmpty()) {
      return UpdateResult.Rejected.NOT_FOUND;
    }
    if (queries.templateType(userId, id).orElseThrow() != input.type()) {
      return UpdateResult.Rejected.TYPE_CHANGED;
    }
    LocalDate today = today();
    queries.deleteFuturePlanned(userId, id, today);
    Optional<UpdateResult.Rejected> refused = checkReferences(userId, input);
    if (refused.isPresent()) {
      status.setRollbackOnly();
      return refused.get();
    }
    queries.updateTemplate(userId, id, input);
    queries.updateRule(userId, id, input.rule());
    queries.deleteDivision(userId, id);
    if (input.division() != null) {
      queries.insertDivision(userId, id, input.division());
    }
    int materialized =
        template.get().active()
            ? materializeAhead(
                userId,
                id,
                input.accountId(),
                input.rule(),
                input.amount(),
                input.division(),
                today)
            : 0;
    return new UpdateResult.Updated(queries.find(userId, id, today).orElseThrow(), materialized);
  }

  // As mesmas conferências da criação, com a conta travada.
  private Optional<UpdateResult.Rejected> checkReferences(UUID userId, RecurrenceInput input) {
    Optional<Boolean> archived = queries.lockAccount(userId, input.accountId());
    if (archived.isEmpty()) {
      return Optional.of(UpdateResult.Rejected.ACCOUNT_NOT_FOUND);
    }
    if (queries.isCryptoAccount(userId, input.accountId())) {
      return Optional.of(UpdateResult.Rejected.CRYPTO_ACCOUNT);
    }
    if (archived.get()) {
      return Optional.of(UpdateResult.Rejected.ACCOUNT_ARCHIVED);
    }
    if (input.categoryId() == null
        || !queries.categoryExists(userId, input.categoryId(), input.type())) {
      return Optional.of(UpdateResult.Rejected.CATEGORY_NOT_FOUND);
    }
    if (input.type() == EntryType.EXPENSE
        && !queries.isCategoryPermitted(userId, input.accountId(), input.categoryId())) {
      return Optional.of(UpdateResult.Rejected.CATEGORY_NOT_PERMITTED);
    }
    return Optional.empty();
  }

  // Como o job: trava só o template e lê "arquivada" sem travar a conta. Repetível, porque a
  // geração pula a data que já existe.
  ReactivateResult reactivate(UUID userId, UUID id) {
    try {
      return reactivateLocked(userId, id);
    } catch (DataIntegrityViolationException e) {
      // A lista do benefício mudou depois que o template nasceu: a ocorrência nova é recusada.
      if (Constraints.isViolated(e, PERMITTED_TRIGGER)) {
        return ReactivateResult.CATEGORY_NOT_PERMITTED;
      }
      throw e;
    }
  }

  private ReactivateResult reactivateLocked(UUID userId, UUID id) {
    return transaction.execute(
        status -> {
          Optional<RecurrenceQueries.LockedTemplate> template = queries.lockTemplate(userId, id);
          if (template.isEmpty()) {
            return ReactivateResult.NOT_FOUND;
          }
          Optional<Boolean> archived =
              queries.isAccountArchived(userId, template.get().accountId());
          if (archived.isEmpty() || archived.get()) {
            return ReactivateResult.ACCOUNT_ARCHIVED;
          }
          queries.activate(userId, id);
          materializeAhead(
              userId,
              id,
              template.get().accountId(),
              template.get().rule(),
              template.get().amount(),
              template.get().division(),
              today());
          return ReactivateResult.REACTIVATED;
        });
  }

  // Trava só o template, como desativar. Falso para template alheio ou inexistente.
  boolean delete(UUID userId, UUID id) {
    return Boolean.TRUE.equals(
        transaction.execute(
            status -> {
              if (queries.lockTemplate(userId, id).isEmpty()) {
                return false;
              }
              queries.deleteTemplateKeepingHistory(userId, id);
              return true;
            }));
  }

  // Lote de templates ativos para o job, depois de afterId.
  List<RecurrenceQueries.TemplateRef> activeTemplates(UUID afterId, int limit) {
    return queries.listActiveTemplates(afterId, limit);
  }

  // Uma rodada do job para um template, numa transação própria. Devolve quantas ocorrências
  // entraram; zero quando o template foi desativado, a conta foi arquivada ou nada é novo.
  int materialize(UUID userId, UUID templateId) {
    Integer inserted =
        transaction.execute(
            JOB_LOCK_TIMEOUT,
            status -> {
              Optional<RecurrenceQueries.LockedTemplate> template =
                  queries.lockTemplate(userId, templateId);
              if (template.isEmpty() || !template.get().active()) {
                return 0;
              }
              // Sem trava de conta: ver a ordem de travas no topo da classe.
              Optional<Boolean> archived =
                  queries.isAccountArchived(userId, template.get().accountId());
              if (archived.isEmpty() || archived.get()) {
                return 0;
              }
              return materializeAhead(
                  userId,
                  templateId,
                  template.get().accountId(),
                  template.get().rule(),
                  template.get().amount(),
                  template.get().division(),
                  today());
            });
    return inserted == null ? 0 : inserted;
  }

  // De max(início, hoje) até hoje mais 12 meses, as duas pontas incluídas. Data que a regra gera
  // antes de hoje nunca é materializada. Com divisão, o filtro vale para a data de cada parcela, e
  // o mês corrente pode sair com só parte delas. Roda dentro de uma transação com o template
  // travado.
  private int materializeAhead(
      UUID userId,
      UUID templateId,
      UUID accountId,
      RecurrenceRule rule,
      BigDecimal amount,
      Division division,
      LocalDate today) {
    Optional<CardBilling> card = queries.findCardBilling(userId, accountId);
    LocalDate from = rule.start().isAfter(today) ? rule.start() : today;
    LocalDate to = today.plusMonths(HORIZON_MONTHS);
    List<RecurrenceQueries.NewOccurrence> occurrences;
    if (division == null) {
      occurrences =
          RecurrenceSchedule.occurrences(rule, from, to).stream()
              .map(
                  date ->
                      new RecurrenceQueries.NewOccurrence(date, cashDate(card, date), null, null))
              .toList();
    } else {
      List<BigDecimal> amounts = SalarySplit.amounts(amount, division);
      List<Integer> days = division.parts().stream().map(DivisionPart::dayOfMonth).toList();
      occurrences =
          RecurrenceSchedule.parcels(rule, days, from, to).stream()
              .map(
                  parcel ->
                      new RecurrenceQueries.NewOccurrence(
                          parcel.date(),
                          cashDate(card, parcel.date()),
                          parcel.order(),
                          amounts.get(parcel.order() - 1)))
              .toList();
    }
    return queries.insertOccurrences(userId, templateId, occurrences);
  }

  // Em conta de cartão a ocorrência fica na data gerada e a caixa vai para o vencimento da fatura
  // (ADR-031). O horizonte e a chave da regra continuam pela data gerada.
  private static LocalDate cashDate(Optional<CardBilling> card, LocalDate date) {
    return card.map(billing -> billing.dueDate(date)).orElse(date);
  }

  private LocalDate today() {
    return clock.instant().atZone(ZONE).toLocalDate();
  }
}
