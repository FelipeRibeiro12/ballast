package com.felipe.ballast.recurrences;

import com.felipe.ballast.web.LockTimeouts;
import com.felipe.ballast.web.UnexpectedErrorLog;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Estende o horizonte de todos os templates ativos, um template por transação (ADR-026). O
// agendamento só existe quando RecurrenceSchedulingConfig liga o @EnableScheduling; o bean e o
// método run existem sempre, para o teste chamar a rodada direto.
@Component
class RecurrenceJob {

  private static final Logger log = LoggerFactory.getLogger(RecurrenceJob.class);

  static final int BATCH_SIZE = 100;

  // Antes de qualquer UUID v7: o primeiro lote começa do início da chave primária.
  private static final UUID BEFORE_ALL = new UUID(0L, 0L);

  private final RecurrenceService recurrences;
  private final int batchSize;

  @Autowired
  RecurrenceJob(RecurrenceService recurrences) {
    this(recurrences, BATCH_SIZE);
  }

  RecurrenceJob(RecurrenceService recurrences, int batchSize) {
    this.recurrences = recurrences;
    this.batchSize = batchSize;
  }

  @Scheduled(cron = "${ballast.recurrence.cron}", zone = "America/Sao_Paulo")
  void run() {
    UUID after = BEFORE_ALL;
    int templates = 0;
    int failures = 0;
    int skipped = 0;
    int inserted = 0;
    while (true) {
      List<RecurrenceQueries.TemplateRef> batch = recurrences.activeTemplates(after, batchSize);
      if (batch.isEmpty()) {
        break;
      }
      for (RecurrenceQueries.TemplateRef ref : batch) {
        templates++;
        try {
          inserted += recurrences.materialize(ref.userId(), ref.templateId());
        } catch (RuntimeException e) {
          if (LockTimeouts.isLockTimeout(e)) {
            // Trava ocupada além do prazo do job: o template fica para a próxima rodada, sem stack.
            skipped++;
            log.warn(
                "recorrência pulada por trava ocupada usuario_id={} template_id={}",
                ref.userId(),
                ref.templateId());
            after = ref.templateId();
            continue;
          }
          // Um template com problema não pode parar os outros. O título fica fora do log.
          failures++;
          UnexpectedErrorLog.error(
              log,
              "falha ao materializar recorrência usuario_id=%s template_id=%s"
                  .formatted(ref.userId(), ref.templateId()),
              e);
        }
        after = ref.templateId();
      }
    }
    log.info(
        "materialização das recorrências templates={} ocorrencias={} puladas={} falhas={}",
        templates,
        inserted,
        skipped,
        failures);
  }
}
