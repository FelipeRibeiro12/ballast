package com.felipe.ballast.imports;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// As linhas lidas do CSV no mesmo formato intermediário do OFX, mais o que só o hash precisa:
// o id da coluna de identificador e a descrição normalizada, ambos sem o apóstrofo de fórmula.
// As três listas têm o tamanho de transactions, na mesma ordem.
record CsvFile(
    List<OfxTransaction> transactions,
    List<String> ids,
    List<String> descriptionKeys,
    int firstRowColumns) {

  // Com id: conta e id, e o mesmo id repetido dá o mesmo hash (o planner marca a segunda linha como
  // duplicada no arquivo, como o FITID). Sem id: conta, data, valor, descrição normalizada e a
  // ordem entre linhas iguais nessas três chaves, para dois cafés iguais no mesmo dia não virarem
  // um e o mesmo extrato reenviado, ou sobreposto, cair em ja_importada. As tags csvid: e csv:
  // entram no texto que vai para o SHA-256 (a coluna só aceita 64 hexa), assim um id forjado com
  // o formato da composição não colide. Linha inválida sem id fica sem hash.
  List<String> hashes(UUID accountId) {
    List<String> hashes = new ArrayList<>();
    Map<String, Integer> occurrences = new HashMap<>();
    for (int i = 0; i < transactions.size(); i++) {
      OfxTransaction row = transactions.get(i);
      if (ids.get(i) != null) {
        hashes.add(sha256("csvid:" + accountId + "|" + ids.get(i)));
      } else if (row.invalidReason() != null) {
        hashes.add(null);
      } else {
        String key = row.date() + "|" + row.amount().toPlainString() + "|" + descriptionKeys.get(i);
        int occurrence = occurrences.merge(key, 1, Integer::sum);
        hashes.add(sha256("csv:" + accountId + "|" + key + "|" + occurrence));
      }
    }
    return hashes;
  }

  private static String sha256(String text) {
    return ImportPlanner.hex(text.getBytes(StandardCharsets.UTF_8));
  }
}
