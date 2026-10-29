package com.felipe.ballast.imports;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// Decide, sem tocar no banco, o que cada linha do arquivo vira: ignorar o que já entrou,
// conciliar com lançamento que o usuário já digitou ou importar (ADR-069). Quem chama traz os
// hashes que já existem e os lançamentos candidatos.
final class ImportPlanner {

  static final int MAX_TITLE_CHARS = 100;
  static final String NO_TITLE = "Sem descrição";
  static final int RECONCILE_DAYS = 3;

  record Candidate(UUID id, String type, BigDecimal amount, LocalDate date) {}

  record PlannedLine(
      OfxTransaction transaction,
      String type,
      BigDecimal amount,
      String title,
      String description,
      String externalHash,
      String proposal,
      String reason,
      UUID entryId) {}

  private ImportPlanner() {}

  static String fileHash(byte[] content) {
    return hex(content);
  }

  // O FITID só é único dentro da conta, então a conta entra no hash. Sem FITID o hash usa data,
  // valor, texto e a ordem da ocorrência entre linhas iguais, para duas compras idênticas no mesmo
  // dia não virarem uma. A tag entra no texto que vai para o SHA-256 (a coluna só aceita 64 hexa,
  // então não dá para prefixar o resultado): sem ela, um FITID forjado com o formato da composição
  // colidiria com o hash de uma linha sem FITID. Linha inválida sem FITID fica sem hash: nunca vira
  // lançamento.
  static List<String> externalHashes(UUID accountId, List<OfxTransaction> transactions) {
    List<String> hashes = new ArrayList<>();
    Map<String, Integer> occurrences = new HashMap<>();
    for (OfxTransaction tx : transactions) {
      if (tx.fitId() != null) {
        hashes.add(hex(("fitid:" + accountId + "|" + tx.fitId()).getBytes(StandardCharsets.UTF_8)));
      } else if (tx.invalidReason() != null) {
        hashes.add(null);
      } else {
        String key = tx.date() + "|" + tx.amount().toPlainString() + "|" + text(tx);
        int occurrence = occurrences.merge(key, 1, Integer::sum);
        hashes.add(
            hex(
                ("nofitid:" + accountId + "|" + key + "|" + occurrence)
                    .getBytes(StandardCharsets.UTF_8)));
      }
    }
    return hashes;
  }

  static List<PlannedLine> plan(
      List<OfxTransaction> transactions,
      List<String> hashes,
      Set<String> alreadyImported,
      List<Candidate> candidates) {
    PlannedLine[] planned = new PlannedLine[transactions.size()];
    Set<String> seen = new HashSet<>();
    List<Integer> open = new ArrayList<>();
    for (int i = 0; i < transactions.size(); i++) {
      OfxTransaction tx = transactions.get(i);
      String hash = hashes.get(i);
      if (tx.invalidReason() != null) {
        planned[i] = line(tx, hash, "ignorar", "invalida", null);
      } else if (!seen.add(hash)) {
        planned[i] = line(tx, hash, "ignorar", "duplicada_no_arquivo", null);
      } else if (alreadyImported.contains(hash)) {
        planned[i] = line(tx, hash, "ignorar", "ja_importada", null);
      } else {
        planned[i] = line(tx, hash, "importar", null, null);
        open.add(i);
      }
    }
    for (Map.Entry<Integer, UUID> match : reconcile(transactions, open, candidates).entrySet()) {
      PlannedLine line = planned[match.getKey()];
      planned[match.getKey()] =
          new PlannedLine(
              line.transaction(),
              line.type(),
              line.amount(),
              line.title(),
              line.description(),
              line.externalHash(),
              "conciliar",
              null,
              match.getValue());
    }
    return List.of(planned);
  }

  private record Key(String type, BigDecimal amount) {}

  // Pareamento guloso: do mais próximo ao mais distante, e cada lado entra uma vez só. A ordem do
  // arquivo e depois a data e o id do candidato desempatam, para o resultado não depender da
  // ordem do banco. Não monta o produto linha x candidato: por distância (0 a 3), cada linha só
  // olha as duas datas à sua volta, e a fila de cada data já está em ordem de id.
  private static Map<Integer, UUID> reconcile(
      List<OfxTransaction> transactions, List<Integer> open, List<Candidate> candidates) {
    // stripTrailingZeros porque 10.0 e 10.00 são o mesmo valor (compareTo) e têm de cair na
    // mesma chave.
    Map<Key, Map<LocalDate, ArrayDeque<Candidate>>> pool = new HashMap<>();
    candidates.stream()
        .sorted(Comparator.comparing(Candidate::id))
        .forEach(
            c ->
                pool.computeIfAbsent(
                        new Key(c.type(), c.amount().stripTrailingZeros()), k -> new HashMap<>())
                    .computeIfAbsent(c.date(), d -> new ArrayDeque<>())
                    .add(c));
    Map<Integer, UUID> matched = new HashMap<>();
    for (int distance = 0; distance <= RECONCILE_DAYS; distance++) {
      for (int index : open) {
        if (matched.containsKey(index)) {
          continue;
        }
        OfxTransaction tx = transactions.get(index);
        Map<LocalDate, ArrayDeque<Candidate>> byDate =
            pool.get(
                new Key(
                    tx.amount().signum() > 0 ? "receita" : "despesa",
                    tx.amount().abs().stripTrailingZeros()));
        if (byDate == null) {
          continue;
        }
        // A data anterior primeiro, que é a que a ordenação por data escolheria.
        Candidate found = take(byDate, tx.date().minusDays(distance));
        if (found == null && distance > 0) {
          found = take(byDate, tx.date().plusDays(distance));
        }
        if (found != null) {
          matched.put(index, found.id());
        }
      }
    }
    return matched;
  }

  private static Candidate take(Map<LocalDate, ArrayDeque<Candidate>> byDate, LocalDate date) {
    ArrayDeque<Candidate> queue = byDate.get(date);
    return queue == null ? null : queue.pollFirst();
  }

  private static PlannedLine line(
      OfxTransaction tx, String hash, String proposal, String reason, UUID entryId) {
    boolean valid = tx.invalidReason() == null;
    return new PlannedLine(
        tx,
        valid ? (tx.amount().signum() > 0 ? "receita" : "despesa") : null,
        valid ? tx.amount().abs() : null,
        title(tx),
        description(tx),
        hash,
        proposal,
        reason,
        entryId);
  }

  static String title(OfxTransaction tx) {
    String base = tx.name() != null ? tx.name() : tx.memo();
    if (base == null) {
      return NO_TITLE;
    }
    if (base.codePointCount(0, base.length()) > MAX_TITLE_CHARS) {
      base = base.substring(0, base.offsetByCodePoints(0, MAX_TITLE_CHARS)).strip();
    }
    return base;
  }

  // O memo vira descrição quando o nome já serviu de título e o memo diz outra coisa.
  static String description(OfxTransaction tx) {
    return tx.name() != null && tx.memo() != null && !tx.memo().equals(tx.name())
        ? tx.memo()
        : null;
  }

  private static String text(OfxTransaction tx) {
    return (tx.name() == null ? "" : tx.name()) + "|" + (tx.memo() == null ? "" : tx.memo());
  }

  static String hex(byte[] bytes) {
    try {
      return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 sempre existe na JVM", e);
    }
  }
}
