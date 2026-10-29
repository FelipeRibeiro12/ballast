package com.felipe.ballast.imports;

import com.felipe.ballast.limits.UserLimit;
import com.felipe.ballast.limits.UserLimits;
import com.felipe.ballast.web.Constraints;
import com.felipe.ballast.web.WriteTransaction;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
class CsvMappingService {

  private static final String NAME_UNIQUE = "mapeamento_csv_usuario_nome_unico";

  enum Failure {
    NOT_FOUND,
    NAME_TAKEN
  }

  sealed interface Result {
    record Ok(CsvMappingQueries.Row row) implements Result {}

    record Failed(Failure failure) implements Result {}
  }

  private final CsvMappingQueries queries;
  private final WriteTransaction transaction;
  private final UserLimits limits;

  CsvMappingService(CsvMappingQueries queries, WriteTransaction transaction, UserLimits limits) {
    this.queries = queries;
    this.transaction = transaction;
    this.limits = limits;
  }

  List<CsvMappingQueries.Row> list(UUID userId) {
    return queries.list(userId);
  }

  Optional<CsvMappingQueries.Row> find(UUID userId, UUID id) {
    return queries.find(userId, id);
  }

  // O teto é conferido com trava dentro da transação (ADR-055). O nome repetido é o unique do
  // banco, que também vale sob corrida; ele chega como DataIntegrityViolation fora do template.
  Result create(UUID userId, String name, CsvMapping mapping) {
    try {
      UUID id =
          transaction.execute(
              status -> {
                limits.checkRoom(userId, UserLimit.CSV_MAPPINGS);
                return queries.insert(userId, name, mapping);
              });
      return new Result.Ok(queries.find(userId, id).orElseThrow());
    } catch (DataIntegrityViolationException e) {
      if (Constraints.isViolated(e, NAME_UNIQUE)) {
        return new Result.Failed(Failure.NAME_TAKEN);
      }
      throw e;
    }
  }

  Result update(UUID userId, UUID id, String name, CsvMapping mapping) {
    try {
      if (queries.update(userId, id, name, mapping) == 0) {
        return new Result.Failed(Failure.NOT_FOUND);
      }
      return new Result.Ok(queries.find(userId, id).orElseThrow());
    } catch (DataIntegrityViolationException e) {
      if (Constraints.isViolated(e, NAME_UNIQUE)) {
        return new Result.Failed(Failure.NAME_TAKEN);
      }
      throw e;
    }
  }

  boolean delete(UUID userId, UUID id) {
    return queries.delete(userId, id) > 0;
  }
}
