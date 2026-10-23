package com.felipe.ballast.crypto;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

// De onde vem a cotação automática. Em produção é a CoinGecko; nos testes, uma fonte em memória
// que pode fingir que caiu. Lança exceção quando a fonte falha; ativo que ela não conhece fica
// fora do mapa.
public interface QuoteSource {

  Map<AssetRef, ProviderQuote> fetch(Collection<AssetRef> assets);

  // Um preço por dia entre from e to, do mais antigo ao mais novo. Vazio quando a fonte não
  // conhece o ativo; exceção quando ela falha.
  List<ProviderQuote> history(AssetRef asset, Instant from, Instant to);
}
