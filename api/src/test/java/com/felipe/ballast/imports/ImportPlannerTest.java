package com.felipe.ballast.imports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ImportPlannerTest {

  private static final LocalDate DAY = LocalDate.of(2026, 10, 10);

  private static OfxTransaction tx(int order, LocalDate date, String amount) {
    return new OfxTransaction(
        order, "OTHER", date, new BigDecimal(amount), null, "X", null, null, null, Map.of());
  }

  private static List<String> hashesFor(List<OfxTransaction> transactions) {
    return ImportPlanner.externalHashes(UUID.randomUUID(), transactions);
  }

  @Test
  void pairsManyEqualLinesWithManyEqualCandidatesWithoutBuildingEveryPair() {
    List<OfxTransaction> transactions = new ArrayList<>();
    for (int i = 0; i < 2000; i++) {
      transactions.add(tx(i, DAY, "-10.00"));
    }
    List<ImportPlanner.Candidate> candidates = new ArrayList<>();
    for (int i = 0; i < 20000; i++) {
      candidates.add(
          new ImportPlanner.Candidate(
              UUID.randomUUID(), "despesa", new BigDecimal("10.00"), DAY.plusDays(i % 3)));
    }
    List<ImportPlanner.PlannedLine> planned =
        assertTimeoutPreemptively(
            Duration.ofSeconds(5),
            () -> ImportPlanner.plan(transactions, hashesFor(transactions), Set.of(), candidates));
    assertThat(planned).filteredOn(l -> "conciliar".equals(l.proposal())).hasSize(2000);
    assertThat(planned.stream().map(ImportPlanner.PlannedLine::entryId).distinct()).hasSize(2000);
  }

  // Referência: o algoritmo antigo, que monta todos os pares e ordena.
  private static Map<Integer, UUID> reference(
      List<OfxTransaction> transactions, List<ImportPlanner.Candidate> candidates) {
    record Pair(int line, ImportPlanner.Candidate candidate, long distance) {}
    List<Pair> pairs = new ArrayList<>();
    for (OfxTransaction tx : transactions) {
      String type = tx.amount().signum() > 0 ? "receita" : "despesa";
      for (ImportPlanner.Candidate c : candidates) {
        long d = Math.abs(java.time.temporal.ChronoUnit.DAYS.between(tx.date(), c.date()));
        if (c.type().equals(type) && c.amount().compareTo(tx.amount().abs()) == 0 && d <= 3) {
          pairs.add(new Pair(tx.order(), c, d));
        }
      }
    }
    pairs.sort(
        Comparator.comparingLong(Pair::distance)
            .thenComparingInt(Pair::line)
            .thenComparing(p -> p.candidate().date())
            .thenComparing(p -> p.candidate().id()));
    Map<Integer, UUID> matched = new HashMap<>();
    Set<UUID> used = new HashSet<>();
    for (Pair p : pairs) {
      if (!matched.containsKey(p.line()) && used.add(p.candidate().id())) {
        matched.put(p.line(), p.candidate().id());
      }
    }
    return matched;
  }

  @Test
  void givesTheSameMatchesAsTheExhaustivePairing() {
    Random random = new Random(42);
    String[] amounts = {"10.00", "10.0", "25.50", "3.00"};
    for (int round = 0; round < 200; round++) {
      List<OfxTransaction> transactions = new ArrayList<>();
      int lines = 1 + random.nextInt(12);
      for (int i = 0; i < lines; i++) {
        String amount = amounts[random.nextInt(amounts.length)];
        transactions.add(
            tx(i, DAY.plusDays(random.nextInt(8)), random.nextBoolean() ? "-" + amount : amount));
      }
      List<ImportPlanner.Candidate> candidates = new ArrayList<>();
      int count = random.nextInt(15);
      for (int i = 0; i < count; i++) {
        candidates.add(
            new ImportPlanner.Candidate(
                UUID.randomUUID(),
                random.nextBoolean() ? "despesa" : "receita",
                new BigDecimal(amounts[random.nextInt(amounts.length)]),
                DAY.plusDays(random.nextInt(8) - 2)));
      }
      Map<Integer, UUID> expected = reference(transactions, candidates);
      Map<Integer, UUID> actual = new HashMap<>();
      List<ImportPlanner.PlannedLine> planned =
          ImportPlanner.plan(transactions, hashesFor(transactions), Set.of(), candidates);
      for (int i = 0; i < planned.size(); i++) {
        if ("conciliar".equals(planned.get(i).proposal())) {
          actual.put(i, planned.get(i).entryId());
        }
      }
      // Linhas repetidas viram duplicada_no_arquivo e nem entram no pareamento: a referência só
      // vale para as linhas abertas.
      Set<Integer> open = new HashSet<>();
      for (int i = 0; i < planned.size(); i++) {
        if (planned.get(i).reason() == null) {
          open.add(i);
        }
      }
      Map<Integer, UUID> openOnly = new HashMap<>();
      List<OfxTransaction> openTx = new ArrayList<>();
      for (int i : open) {
        OfxTransaction t = transactions.get(i);
        openTx.add(tx(i, t.date(), t.amount().toPlainString()));
      }
      openOnly.putAll(reference(openTx, candidates));
      assertThat(actual).as("rodada %d", round).isEqualTo(openOnly);
      assertThat(expected).isNotNull();
    }
  }
}
