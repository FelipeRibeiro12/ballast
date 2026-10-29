package com.felipe.ballast.balanceviews;

import com.felipe.ballast.accounts.AccountType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

@Schema(name = "VisaoSaldo")
public record BalanceViewResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 100)
        String titulo,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<AccountType> tipos) {

  static BalanceViewResponse from(BalanceViewQueries.View view) {
    return new BalanceViewResponse(view.id(), view.title(), view.types());
  }
}
