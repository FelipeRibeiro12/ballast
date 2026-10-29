package com.felipe.ballast.entries;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionStatus;

// Porta do módulo de importação para criar lançamento. Passa pelo mesmo EntryService do
// POST /lancamentos, então as regras de conta, categoria, cartão e as duas datas valem iguais
// (ADR-069). Só roda dentro de uma transação já aberta por quem chama.
@Component
public class ImportedEntries {

  public enum Failure {
    ACCOUNT_NOT_FOUND,
    ACCOUNT_ARCHIVED,
    ACCOUNT_NOT_ACCEPTED,
    CATEGORY_NOT_FOUND,
    CATEGORY_NOT_PERMITTED,
    BALANCE_OUT_OF_RANGE
  }

  // Tipo como no JSON ("receita" ou "despesa"): o EntryType não sai deste pacote.
  public record NewEntry(
      String type,
      String title,
      String description,
      BigDecimal amount,
      UUID categoryId,
      UUID accountId,
      LocalDate date,
      String externalHash,
      UUID importLineId) {}

  public record Created(UUID id, Failure failure) {
    public boolean saved() {
      return failure == null;
    }
  }

  private final EntryService entries;

  ImportedEntries(EntryService entries) {
    this.entries = entries;
  }

  // Em caso de recusa a transação fica marcada para desfazer pelo próprio serviço quando o saldo
  // estoura; nas outras, quem chama decide desfazer.
  public Created create(TransactionStatus status, UUID userId, NewEntry entry) {
    EntryType type =
        switch (entry.type()) {
          case "receita" -> EntryType.INCOME;
          case "despesa" -> EntryType.EXPENSE;
          default -> throw new IllegalArgumentException("tipo de importação inválido");
        };
    EntryInput input =
        new EntryInput(
            type,
            entry.title(),
            entry.description(),
            entry.amount(),
            entry.categoryId(),
            entry.accountId(),
            entry.date());
    EntryService.SaveResult result =
        entries.createImported(status, userId, input, entry.externalHash(), entry.importLineId());
    if (result instanceof EntryService.SaveResult.Saved saved) {
      return new Created(saved.entry().id(), null);
    }
    EntryService.SaveResult.Rejected rejected = (EntryService.SaveResult.Rejected) result;
    return new Created(null, failureOf(rejected));
  }

  private static Failure failureOf(EntryService.SaveResult.Rejected rejected) {
    return switch (rejected) {
      case ACCOUNT_NOT_FOUND -> Failure.ACCOUNT_NOT_FOUND;
      case ACCOUNT_ARCHIVED -> Failure.ACCOUNT_ARCHIVED;
      case CRYPTO_ACCOUNT, INVESTMENT_ACCOUNT -> Failure.ACCOUNT_NOT_ACCEPTED;
      case CATEGORY_NOT_FOUND -> Failure.CATEGORY_NOT_FOUND;
      case CATEGORY_NOT_PERMITTED -> Failure.CATEGORY_NOT_PERMITTED;
      case BALANCE_OUT_OF_RANGE -> Failure.BALANCE_OUT_OF_RANGE;
      default -> throw new IllegalStateException("recusa inesperada ao criar lançamento importado");
    };
  }
}
