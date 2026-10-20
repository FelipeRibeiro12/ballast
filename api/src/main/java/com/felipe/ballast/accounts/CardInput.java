package com.felipe.ballast.accounts;

import java.math.BigDecimal;
import java.util.UUID;

// Dados do cartão já validados na borda. Id nulo quer dizer que o texto enviado não era um UUID;
// o serviço trata como conta de pagamento que não existe.
record CardInput(BigDecimal limit, int closingDay, int dueDay, UUID paymentAccountId) {}
