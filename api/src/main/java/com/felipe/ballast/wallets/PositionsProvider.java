package com.felipe.ballast.wallets;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

// De onde vêm NFT e posição DeFi de um endereço (ADR-081). Existe para o teste trocar por um fake e
// para a troca de provedor ser só uma implementação nova.
public interface PositionsProvider {

  // kind é nft ou defi. category segue o CHECK da V44. quantity e valueUsdCents podem faltar:
  // o provedor nem sempre sabe. Empréstimo vem com valor negativo.
  record Position(
      String kind,
      String category,
      String protocol,
      String name,
      String detail,
      BigDecimal quantity,
      Long valueUsdCents) {}

  // Falso sem chave: nenhuma chamada acontece e a tela diz que a leitura está desligada.
  boolean isConfigured();

  // Vazio quando o provedor não respondeu: quem chama mantém as posições antigas. Lista vazia
  // quer dizer que o endereço não tem nada ou que a rede não é coberta.
  Optional<List<Position>> read(WalletNetwork network, String address);
}
