-- ADR-081. NFT e posição DeFi (liquidez, stake, depósito, empréstimo) de uma carteira observada.
-- É retrato da última leitura: cada leitura apaga as linhas da carteira e grava as novas. Não é
-- movimento (não entra no saldo) e não tem custo.
CREATE TABLE posicao_carteira (
    id uuid PRIMARY KEY,
    usuario_id uuid NOT NULL REFERENCES usuario (id) ON DELETE CASCADE,
    carteira_id uuid NOT NULL,
    tipo text NOT NULL,
    categoria text NOT NULL,
    protocolo text,
    nome text NOT NULL,
    detalhe text,
    quantidade numeric(38, 18),
    valor_usd_centavos bigint,
    atualizado_em timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT posicao_carteira_tipo_valido CHECK (tipo IN ('nft', 'defi')),
    CONSTRAINT posicao_carteira_categoria_valida CHECK (
        categoria IN ('nft', 'liquidez', 'stake', 'deposito', 'emprestimo', 'rendimento',
                      'bloqueado', 'recompensa', 'outro')
    ),
    CONSTRAINT posicao_carteira_textos_tamanho CHECK (
        char_length(nome) BETWEEN 1 AND 100
        AND (protocolo IS NULL OR char_length(protocolo) <= 100)
        AND (detalhe IS NULL OR char_length(detalhe) <= 100)
    ),
    CONSTRAINT posicao_carteira_carteira_do_usuario FOREIGN KEY (carteira_id, usuario_id)
        REFERENCES carteira_observada (id, usuario_id) ON DELETE CASCADE
);

CREATE INDEX posicao_carteira_carteira_idx ON posicao_carteira (usuario_id, carteira_id);
