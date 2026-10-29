package com.felipe.ballast.imports;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// As linhas lidas da fatura no mesmo formato intermediário do OFX, mais a descrição normalizada
// que só o hash precisa. As duas listas têm o mesmo tamanho, na mesma ordem.
record PdfFile(List<OfxTransaction> transactions, List<String> descriptionKeys) {

  // Conta, data, valor com sinal, descrição normalizada e a ordem entre linhas iguais nessas
  // chaves: duas compras idênticas no mesmo dia não viram uma, e a mesma fatura reenviada (ou
  // outra com as mesmas compras) cai em ja_importada. A tag pdf: entra no texto que vai para o
  // SHA-256 para não colidir com os hashes de OFX e CSV. Linha inválida fica sem hash.
  List<String> hashes(UUID accountId) {
    List<String> hashes = new ArrayList<>();
    Map<String, Integer> occurrences = new HashMap<>();
    for (int i = 0; i < transactions.size(); i++) {
      OfxTransaction row = transactions.get(i);
      if (row.invalidReason() != null) {
        hashes.add(null);
        continue;
      }
      String key = row.date() + "|" + row.amount().toPlainString() + "|" + descriptionKeys.get(i);
      int occurrence = occurrences.merge(key, 1, Integer::sum);
      hashes.add(
          ImportPlanner.hex(
              ("pdf:" + accountId + "|" + key + "|" + occurrence)
                  .getBytes(StandardCharsets.UTF_8)));
    }
    return hashes;
  }
}
