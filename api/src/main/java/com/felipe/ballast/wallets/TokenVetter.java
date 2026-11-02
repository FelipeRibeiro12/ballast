package com.felipe.ballast.wallets;

import java.util.Collection;
import java.util.Set;

// Diz quais contratos são token de verdade. Token que ninguém cota é, na prática, spam de airdrop.
// Lança exceção quando a fonte falha: quem chama trata como leitura parcial.
public interface TokenVetter {

  // Os contratos da entrada que têm cotação. EVM em minúsculas, como entram.
  Set<String> priced(WalletNetwork network, Collection<String> contracts);
}
