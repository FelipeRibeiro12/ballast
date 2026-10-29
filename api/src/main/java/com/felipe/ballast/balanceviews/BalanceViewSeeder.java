package com.felipe.ballast.balanceviews;

import com.felipe.ballast.identity.UserRegistered;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

// Roda dentro da transação do cadastro, como o CategorySeeder: se falhar, o usuário também não é
// criado. Com a visão padrão criada aqui, o GET /visoes-saldo não precisa escrever.
@Component
class BalanceViewSeeder {

  private final BalanceViewQueries queries;

  BalanceViewSeeder(BalanceViewQueries queries) {
    this.queries = queries;
  }

  @EventListener
  void seed(UserRegistered event) {
    queries.insert(
        event.userId(), BalanceViewService.DEFAULT_TITLE, BalanceViewService.defaultTypes());
  }
}
