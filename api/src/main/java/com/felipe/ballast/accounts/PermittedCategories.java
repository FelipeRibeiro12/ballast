package com.felipe.ballast.accounts;

import com.felipe.ballast.web.CanonicalUuid;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

// Lista de categorias permitidas que chega do cliente (ADR-034). O tamanho é conferido antes, na
// anotação do DTO; aqui só a forma dos ids e a repetição.
final class PermittedCategories {

  static final int MAX = 50;
  static final String SIZE_MESSAGE = "categoriasPermitidas deve ter no máximo 50 itens";

  sealed interface Parsed {
    record Ids(List<UUID> ids) implements Parsed {}

    enum Refused implements Parsed {
      MALFORMED,
      REPEATED
    }
  }

  private PermittedCategories() {}

  // Id malformado e id inexistente dão a mesma resposta no controller, então aqui basta dizer que
  // não é um UUID canônico. A repetição compara o UUID já lido, para maiúscula e minúscula do
  // mesmo id contarem como repetidos.
  static Parsed parse(List<String> raw) {
    List<UUID> ids = new java.util.ArrayList<>(raw.size());
    for (String text : raw) {
      Optional<UUID> id = CanonicalUuid.parse(text);
      if (id.isEmpty()) {
        return Parsed.Refused.MALFORMED;
      }
      ids.add(id.get());
    }
    Set<UUID> distinct = new HashSet<>(ids);
    if (distinct.size() != ids.size()) {
      return Parsed.Refused.REPEATED;
    }
    return new Parsed.Ids(List.copyOf(ids));
  }
}
