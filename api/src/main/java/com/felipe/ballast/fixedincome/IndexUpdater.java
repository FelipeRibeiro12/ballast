package com.felipe.ballast.fixedincome;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

// Baixa do Banco Central o que falta de cada série (ADR-037). A fonte fora do ar nunca sobe como
// erro: o cálculo segue com o que já está guardado e diz até que data há índice.
@Component
public class IndexUpdater {

  private static final Logger log = LoggerFactory.getLogger(IndexUpdater.class);

  private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

  // O SGS recusa janela maior que 10 anos nas séries diárias; 5 deixa folga.
  private static final int CHUNK_YEARS = 5;

  private static final int CDI_GAP_DAYS = 5;

  private final IndexSource source;
  private final IndexQueries queries;
  private final Clock clock;

  IndexUpdater(IndexSource source, IndexQueries queries, Clock clock) {
    this.source = source;
    this.queries = queries;
    this.clock = clock;
  }

  // Desde a primeira aplicação cadastrada ou um ano atrás, o que for antes.
  public void update() {
    LocalDate today = today();
    LocalDate yearAgo = today.minusYears(1);
    LocalDate from =
        queries.earliestInvestment().filter(earliest -> earliest.isBefore(yearAgo)).orElse(yearAgo);
    updateFrom(from);
  }

  // Preenche antes do primeiro valor guardado (aplicação antiga) e depois do último (dias novos).
  void updateFrom(LocalDate from) {
    LocalDate today = today();
    for (IndexSeries series : IndexSeries.values()) {
      try {
        Optional<LocalDate> first = queries.first(series);
        Optional<LocalDate> last = queries.last(series);
        if (first.isEmpty() || last.isEmpty()) {
          fetchRange(series, from, today);
          continue;
        }
        if (missingBefore(series, from, first.get())) {
          fetchRange(series, from, first.get().minusDays(1));
        }
        if (last.get().isBefore(today)) {
          fetchRange(series, last.get().plusDays(1), today);
        }
      } catch (RuntimeException e) {
        // Sem a mensagem da exceção: ela pode trazer a URL inteira, e a série basta para saber.
        log.warn(
            "índice do banco central não atualizado: serie={} erro={}",
            series.value(),
            e.getClass().getSimpleName());
      }
    }
  }

  // Sem folga, a janela vazia antes do primeiro valor (fim de semana, feriado, ou o IPCA datado do
  // dia 1º) seria pedida de novo em toda rodada. Carnaval dá até 4 dias seguidos sem CDI.
  private static boolean missingBefore(IndexSeries series, LocalDate from, LocalDate first) {
    if (series == IndexSeries.IPCA) {
      return YearMonth.from(from).isBefore(YearMonth.from(first));
    }
    return from.isBefore(first.minusDays(CDI_GAP_DAYS));
  }

  private void fetchRange(IndexSeries series, LocalDate from, LocalDate to) {
    LocalDate start = from;
    while (!start.isAfter(to)) {
      LocalDate end = start.plusYears(CHUNK_YEARS).minusDays(1);
      if (end.isAfter(to)) {
        end = to;
      }
      queries.insert(series, source.fetch(series, start, end));
      start = end.plusDays(1);
    }
  }

  private LocalDate today() {
    return clock.instant().atZone(ZONE).toLocalDate();
  }
}
