package com.felipe.ballast.wallets;

// Sem causa e sem mensagem com dado: a exceção do cliente HTTP carrega a URL, que tem a chave do
// provedor, e o corpo da requisição tem o endereço.
public class IndexerUnavailableException extends RuntimeException {

  // O texto é o que vai para carteira_observada.erro_ultima_sync (CHECK da V36).
  public enum Reason {
    UNAVAILABLE("indexador_indisponivel"),
    INVALID_RESPONSE("resposta_invalida"),
    NO_KEY("sem_chave");

    private final String code;

    Reason(String code) {
      this.code = code;
    }

    public String code() {
      return code;
    }
  }

  private final Reason reason;

  public IndexerUnavailableException(Reason reason) {
    super(reason.code(), null, false, false);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
