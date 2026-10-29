package com.felipe.ballast.wallets;

import java.math.BigDecimal;
import java.util.List;

// Quem lê o saldo de um endereço na rede (ADR-071). Existe para o teste trocar por um fake e para
// a troca de provedor ser só uma implementação nova.
public interface WalletIndexer {

  // contrato nulo é o ativo nativo da rede. quantidade já vem dividida pelos decimais. simbolo é
  // texto de terceiro: quem recebe trunca e limpa.
  record Holding(String contract, String symbol, int decimals, BigDecimal quantity) {}

  // partial é verdadeiro quando o indexador deixou tokens de fora (teto por leitura). Num
  // resultado parcial, o ativo que não aparece não está zerado: só não foi visto.
  record Reading(List<Holding> holdings, boolean partial) {}

  // Falha de rede, timeout, 4xx, 5xx ou resposta fora do formato vira IndexerUnavailableException.
  Reading read(WalletNetwork network, String address);

  // Falso sem chave: o job não roda e a leitura manual responde que não está configurado.
  boolean isConfigured();
}
