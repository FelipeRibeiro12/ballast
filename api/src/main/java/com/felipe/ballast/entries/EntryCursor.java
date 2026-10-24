package com.felipe.ballast.entries;

import com.felipe.ballast.web.CanonicalUuid;
import com.felipe.ballast.web.Paging;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.UUID;

// Posição do último lançamento da página, na ordem da listagem (data_caixa, criado_em, id).
// Vale como ponto de partida e nada mais: a consulta continua filtrando por usuario_id.
record EntryCursor(LocalDate cashDate, Instant createdAt, UUID id) {

  static EntryCursor of(EntrySummary last) {
    return new EntryCursor(last.cashDate(), last.createdAt(), last.id());
  }

  String encode() {
    return Paging.encode(cashDate.toString(), createdAt.toString(), id.toString());
  }

  static Optional<EntryCursor> parse(String cursor) {
    Optional<String[]> parts = Paging.decode(cursor, 3);
    if (parts.isEmpty()) {
      return Optional.empty();
    }
    Optional<LocalDate> date = IsoDates.parse(parts.get()[0]);
    Optional<UUID> id = CanonicalUuid.parse(parts.get()[2]);
    if (date.isEmpty() || id.isEmpty()) {
      return Optional.empty();
    }
    try {
      return Optional.of(new EntryCursor(date.get(), Instant.parse(parts.get()[1]), id.get()));
    } catch (DateTimeParseException e) {
      return Optional.empty();
    }
  }
}
