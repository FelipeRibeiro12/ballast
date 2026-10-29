package com.felipe.ballast.balanceviews;

import com.felipe.ballast.accounts.AccountType;
import com.felipe.ballast.limits.UserLimit;
import com.felipe.ballast.limits.UserLimits;
import com.felipe.ballast.web.WriteTransaction;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
class BalanceViewService {

  static final String DEFAULT_TITLE = "Total sem cartão";

  enum Failure {
    NOT_FOUND,
    LAST_VIEW
  }

  private final BalanceViewQueries queries;
  private final WriteTransaction transaction;
  private final UserLimits limits;

  BalanceViewService(BalanceViewQueries queries, WriteTransaction transaction, UserLimits limits) {
    this.queries = queries;
    this.transaction = transaction;
    this.limits = limits;
  }

  // Quem ainda não tem visão recebe a padrão: todos os tipos menos cartão de crédito. A trava do
  // teto serializa duas primeiras leituras juntas, e a segunda enxerga a linha da primeira.
  List<BalanceViewQueries.View> list(UUID userId) {
    if (queries.count(userId) == 0) {
      transaction.execute(
          status -> {
            limits.checkRoom(userId, UserLimit.BALANCE_VIEWS);
            if (queries.count(userId) == 0) {
              queries.insert(userId, DEFAULT_TITLE, defaultTypes());
            }
            return null;
          });
    }
    return queries.list(userId);
  }

  void create(UUID userId, String title, List<AccountType> types) {
    transaction.execute(
        status -> {
          limits.checkRoom(userId, UserLimit.BALANCE_VIEWS);
          queries.insert(userId, title.strip(), types.stream().distinct().toList());
          return null;
        });
  }

  boolean update(UUID userId, UUID id, String title, List<AccountType> types) {
    return queries.update(userId, id, title.strip(), types.stream().distinct().toList()) > 0;
  }

  // A última visão não se apaga: o carrossel sempre tem ao menos um cartão.
  Optional<Failure> delete(UUID userId, UUID id) {
    return transaction.execute(
        status -> {
          long total = queries.countLocked(userId);
          if (total == 0) {
            return Optional.of(Failure.NOT_FOUND);
          }
          if (total == 1) {
            return Optional.of(Failure.LAST_VIEW);
          }
          return queries.delete(userId, id) == 0
              ? Optional.of(Failure.NOT_FOUND)
              : Optional.empty();
        });
  }

  static List<AccountType> defaultTypes() {
    return Arrays.stream(AccountType.values())
        .filter(type -> type != AccountType.CREDIT_CARD)
        .toList();
  }
}
