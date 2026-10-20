package com.felipe.ballast.recurrences;

import com.felipe.ballast.web.WriteTransaction;
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
// conta, nunca o template, então não fecham ciclo com este serviço.
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

  sealed interface CreateResult {
    record Created(RecurrenceSummary recurrence, int materialized) implements CreateResult {}

    enum Rejected implements CreateResult {
      ACCOUNT_NOT_FOUND,
      ACCOUNT_ARCHIVED,
      CATEGORY_NOT_FOUND
    }
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
    if (archived.get()) {
      return CreateResult.Rejected.ACCOUNT_ARCHIVED;
    }
    if (input.categoryId() == null
        || !queries.categoryExists(userId, input.categoryId(), input.type())) {
      return CreateResult.Rejected.CATEGORY_NOT_FOUND;
    }
    UUID ruleId = queries.insertRule(userId, input.rule());
    UUID templateId = queries.insertTemplate(userId, input, ruleId);
    LocalDate today = today();
    int materialized = materializeAhead(userId, templateId, input.rule(), today);
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
              return materializeAhead(userId, templateId, template.get().rule(), today());
            });
    return inserted == null ? 0 : inserted;
  }

  // De max(início, hoje) até hoje mais 12 meses, as duas pontas incluídas. Data que a regra gera
  // antes de hoje nunca é materializada. Roda dentro de uma transação com o template travado.
  private int materializeAhead(UUID userId, UUID templateId, RecurrenceRule rule, LocalDate today) {
    LocalDate from = rule.start().isAfter(today) ? rule.start() : today;
    LocalDate to = today.plusMonths(HORIZON_MONTHS);
    List<LocalDate> dates = RecurrenceSchedule.occurrences(rule, from, to);
    return queries.insertOccurrences(userId, templateId, dates);
  }

  private LocalDate today() {
    return clock.instant().atZone(ZONE).toLocalDate();
  }
}
