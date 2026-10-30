package com.felipe.ballast.wallets;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Optional;

// O que a interface mostra como motivo da leitura. Os nomes são o contrato com o web: não
// renomeie. Nunca carrega URL, chave nem texto do indexador.
@Schema(name = "MotivoLeitura")
public enum ReadingReason {
  INDEXADOR_NAO_CONFIGURADO,
  INDEXADOR_FORA_DO_AR,
  INDEXADOR_RECUSOU_A_CHAVE,
  INDEXADOR_COTA_ESGOTADA,
  RESPOSTA_INVALIDA,
  // Só na resposta de sincronizar, e só quando não há falha gravada para mostrar no lugar.
  LIMITE_DE_60S;

  // Código gravado em carteira_observada.erro_ultima_sync (CHECK da V41).
  static Optional<ReadingReason> fromCode(String code) {
    if (code == null) {
      return Optional.empty();
    }
    return Optional.of(
        switch (code) {
          case "sem_chave" -> INDEXADOR_NAO_CONFIGURADO;
          case "indexador_recusou" -> INDEXADOR_RECUSOU_A_CHAVE;
          case "indexador_limite" -> INDEXADOR_COTA_ESGOTADA;
          case "resposta_invalida" -> RESPOSTA_INVALIDA;
          default -> INDEXADOR_FORA_DO_AR;
        });
  }
}
