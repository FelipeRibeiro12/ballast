package com.felipe.ballast.entries;

import com.felipe.ballast.web.WriteTransaction;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionStatus;

// Assume entrada já validada e normalizada na borda. Todo método recebe o dono vindo da sessão.
//
// Ordem de travas, sempre a mesma para ninguém formar ciclo:
//   criar:  conta menor id, conta maior id, depois linhas novas (ninguém mais as enxerga);
//   apagar: linha da transferência, as duas pernas (ordem de id), as duas contas (ordem de id).
// O PUT e o DELETE de /lancamentos travam a perna e param nela se for perna de transferência,
// sem chegar às contas, então nunca esperam por quem espera pela perna.
@Service
class TransferService {

  static final String DEFAULT_TITLE = "Transferência";

  sealed interface CreateResult {
    record Created(TransferSummary transfer) implements CreateResult {}

    enum Rejected implements CreateResult {
      ACCOUNT_NOT_FOUND,
      ACCOUNT_ARCHIVED,
      SAME_ACCOUNT,
      CARD_AS_ORIGIN,
      BENEFIT_ACCOUNT,
      CRYPTO_ACCOUNT,
      INVESTMENT_ACCOUNT,
      BALANCE_OUT_OF_RANGE
    }
  }

  private final EntryRepository entries;
  private final TransferRepository transfers;
  private final EntryQueries entryQueries;
  private final TransferQueries queries;
  private final WriteTransaction transaction;

  TransferService(
      EntryRepository entries,
      TransferRepository transfers,
      EntryQueries entryQueries,
      TransferQueries queries,
      WriteTransaction transaction) {
    this.entries = entries;
    this.transfers = transfers;
    this.entryQueries = entryQueries;
    this.queries = queries;
    this.transaction = transaction;
  }

  CreateResult create(UUID userId, TransferInput input) {
    if (input.fromAccountId() == null || input.toAccountId() == null) {
      return CreateResult.Rejected.ACCOUNT_NOT_FOUND;
    }
    if (input.fromAccountId().equals(input.toAccountId())) {
      return CreateResult.Rejected.SAME_ACCOUNT;
    }
    return transaction.execute(status -> createLocked(status, userId, input));
  }

  Optional<TransferSummary> find(UUID userId, UUID id) {
    return queries.find(userId, id);
  }

  // NOT_FOUND para transferência de outro usuário ou inexistente, sem distinguir. Apagar a
  // entrada de uma conta também pode tirar o saldo dela da faixa, por isso confere como as
  // outras escritas.
  EntryService.DeleteResult delete(UUID userId, UUID id) {
    return transaction.execute(status -> deleteLocked(status, userId, id));
  }

  private CreateResult createLocked(TransactionStatus status, UUID userId, TransferInput input) {
    Optional<Boolean> fromArchived = Optional.empty();
    Optional<Boolean> toArchived = Optional.empty();
    for (UUID account : EntryService.lockOrder(input.fromAccountId(), input.toAccountId())) {
      Optional<Boolean> archived = entryQueries.lockAccount(userId, account);
      if (account.equals(input.fromAccountId())) {
        fromArchived = archived;
      } else {
        toArchived = archived;
      }
    }
    // Mesma mensagem para origem e destino: dizer qual falhou revelaria que a outra existe.
    if (fromArchived.isEmpty() || toArchived.isEmpty()) {
      return CreateResult.Rejected.ACCOUNT_NOT_FOUND;
    }
    if (fromArchived.get() || toArchived.get()) {
      return CreateResult.Rejected.ACCOUNT_ARCHIVED;
    }
    // Depois de "não encontrada": a resposta para conta alheia não pode revelar o tipo dela.
    if (entryQueries.isBenefitAccount(userId, input.fromAccountId())
        || entryQueries.isBenefitAccount(userId, input.toAccountId())) {
      return CreateResult.Rejected.BENEFIT_ACCOUNT;
    }
    if (entryQueries.isCryptoAccount(userId, input.fromAccountId())
        || entryQueries.isCryptoAccount(userId, input.toAccountId())) {
      return CreateResult.Rejected.CRYPTO_ACCOUNT;
    }
    if (entryQueries.isInvestmentAccount(userId, input.fromAccountId())
        || entryQueries.isInvestmentAccount(userId, input.toAccountId())) {
      return CreateResult.Rejected.INVESTMENT_ACCOUNT;
    }
    // Saída do cartão não entra em nenhuma fatura, mas baixaria o saldo dele: a fatura ficaria paga
    // com dívida no saldo. Só o pagamento (cartão como destino) é transferência.
    if (entryQueries.findCardBilling(userId, input.fromAccountId()).isPresent()) {
      return CreateResult.Rejected.CARD_AS_ORIGIN;
    }
    String title = input.title() == null ? DEFAULT_TITLE : input.title();
    UUID outId = saveLeg(userId, input, title, input.fromAccountId());
    UUID inId = saveLeg(userId, input, title, input.toAccountId());
    UUID id =
        transfers
            .saveAndFlush(
                new Transfer(userId, outId, inId, input.fromAccountId(), input.toAccountId()))
            .getId();
    // Com a ligação gravada, o saldo já conta cada perna com o sinal certo.
    if (!entryQueries.balanceFits(userId, input.fromAccountId())
        || !entryQueries.balanceFits(userId, input.toAccountId())) {
      status.setRollbackOnly();
      return CreateResult.Rejected.BALANCE_OUT_OF_RANGE;
    }
    return queries
        .find(userId, id)
        .<CreateResult>map(CreateResult.Created::new)
        .orElseThrow(() -> new IllegalStateException("transferência gravada não encontrada"));
  }

  // Perna sem categoria e com a mesma data nas duas datas, também em conta de cartão: pagar a
  // fatura é dinheiro se movendo no dia (ADR-031), sem a regra da fatura.
  private UUID saveLeg(UUID userId, TransferInput input, String title, UUID accountId) {
    EntryInput leg =
        new EntryInput(
            EntryType.TRANSFER,
            title,
            input.description(),
            input.amount(),
            null,
            accountId,
            input.date());
    return entries.saveAndFlush(new Entry(userId, leg, input.date())).getId();
  }

  private EntryService.DeleteResult deleteLocked(TransactionStatus status, UUID userId, UUID id) {
    Optional<TransferQueries.Legs> legs = queries.lockTransfer(userId, id);
    if (legs.isEmpty()) {
      return EntryService.DeleteResult.NOT_FOUND;
    }
    UUID outId = legs.get().outEntryId();
    UUID inId = legs.get().inEntryId();
    queries.lockEntries(userId, List.of(outId, inId));
    for (UUID account :
        EntryService.lockOrder(legs.get().fromAccountId(), legs.get().toAccountId())) {
      entryQueries.lockAccount(userId, account);
    }
    // A ligação sai primeiro: as FKs recusam apagar perna com a ligação viva.
    if (transfers.deleteOwned(userId, id) == 0
        || entries.deleteOwned(userId, outId) == 0
        || entries.deleteOwned(userId, inId) == 0) {
      status.setRollbackOnly();
      return EntryService.DeleteResult.NOT_FOUND;
    }
    if (!entryQueries.balanceFits(userId, legs.get().fromAccountId())
        || !entryQueries.balanceFits(userId, legs.get().toAccountId())) {
      status.setRollbackOnly();
      return EntryService.DeleteResult.BALANCE_OUT_OF_RANGE;
    }
    return EntryService.DeleteResult.DELETED;
  }
}
