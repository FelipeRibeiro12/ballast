package com.felipe.ballast.wallets;

import com.felipe.ballast.crypto.AssetRef;
import com.felipe.ballast.crypto.QuoteSource;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

// Usa a mesma fonte de cotação do patrimônio (ADR-040): se a CoinGecko cota o contrato, o token
// existe e entra no patrimônio. Só rede e contrato saem daqui, nunca o endereço da carteira.
@Component
class QuoteTokenVetter implements TokenVetter {

  private final QuoteSource source;

  QuoteTokenVetter(QuoteSource source) {
    this.source = source;
  }

  @Override
  public Set<String> priced(WalletNetwork network, Collection<String> contracts) {
    if (contracts.isEmpty()) {
      return Set.of();
    }
    List<AssetRef> refs =
        contracts.stream().map(contract -> new AssetRef(network.asset(), contract)).toList();
    Set<String> priced = new HashSet<>();
    source.fetch(refs).keySet().forEach(ref -> priced.add(ref.contract()));
    return priced;
  }
}
