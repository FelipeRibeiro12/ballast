-- ADR-074. Marcador do rendimento diário do cofrinho: uma linha por conta e dia de crédito. O
-- unique (conta_id, data) é o que impede o job e o POST de lançar o mesmo dia duas vezes. A maior
-- data da conta é o L do ADR-074, o último dia já creditado.
-- Id sem DEFAULT, gerado na aplicação, como nas outras tabelas.

-- lancamento_id fica nulo quando o dia só avançou a janela (saldo zero ou negativo) e quando o
-- usuário apaga o lançamento. Apagar o lançamento mantém o marcador, senão o job recriaria o dia.
-- A FK é composta para o lançamento ser do mesmo usuário; ON DELETE SET NULL (lancamento_id), com
-- a lista de colunas (Postgres 15+), anula só essa coluna. Sem a lista o SET NULL anularia
-- usuario_id também, que é NOT NULL, e o DELETE do lançamento falharia.
-- CASCADE na conta: o marcador só some com ela.
CREATE TABLE rendimento_cofrinho (
    id uuid PRIMARY KEY,
    usuario_id uuid NOT NULL REFERENCES usuario (id) ON DELETE CASCADE,
    conta_id uuid NOT NULL,
    data date NOT NULL,
    lancamento_id uuid,
    criado_em timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT rendimento_cofrinho_conta_dia_unico UNIQUE (conta_id, data),
    CONSTRAINT rendimento_cofrinho_conta_do_usuario FOREIGN KEY (conta_id, usuario_id)
        REFERENCES conta (id, usuario_id) ON DELETE CASCADE,
    CONSTRAINT rendimento_cofrinho_lancamento_do_usuario FOREIGN KEY (lancamento_id, usuario_id)
        REFERENCES lancamento (id, usuario_id) ON DELETE SET NULL (lancamento_id)
);

-- A leitura do job é a maior data da conta, e o dono sempre filtra por usuario_id.
CREATE INDEX rendimento_cofrinho_usuario_conta_data
    ON rendimento_cofrinho (usuario_id, conta_id, data DESC);

-- Sem GRANT: o ALTER DEFAULT PRIVILEGES da V3 cobre a tabela nova.

-- Para desfazer (o Flyway Community não tem down; rode à mão).
-- DROP TABLE rendimento_cofrinho;
-- e apague a linha de flyway_schema_history da versão 38.
