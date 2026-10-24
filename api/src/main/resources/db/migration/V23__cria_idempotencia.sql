-- ADR-056. Chave de idempotência das escritas de dinheiro: o cabeçalho Idempotency-Key de um POST
-- vira uma linha aqui, escopada por usuário. A linha nasce "em andamento" (status e resposta
-- nulos) e vira a resposta guardada quando a escrita termina com 2xx; resposta de erro apaga a
-- linha, para a chave poder ser reusada depois da correção.
-- A chave primária é o índice único (usuario_id, chave) que serializa duas requisições iguais.
-- rota e corpo_hash dizem o que a chave protegeu: o mesmo par com rota ou corpo diferente é
-- recusado. corpo_hash é o SHA-256 em hexadecimal dos bytes do corpo da requisição.
-- A limpeza das linhas com mais de 24 h roda no job diário de limpeza; o índice em criada_em
-- serve a ele. Sem GRANT: o ALTER DEFAULT PRIVILEGES da V3 cobre a tabela nova.
CREATE TABLE idempotencia (
    usuario_id uuid NOT NULL REFERENCES usuario (id) ON DELETE CASCADE,
    chave varchar(80) NOT NULL,
    rota varchar(200) NOT NULL,
    corpo_hash char(64) NOT NULL,
    status smallint,
    corpo_resposta text,
    criada_em timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (usuario_id, chave),
    CONSTRAINT idempotencia_chave_formato CHECK (chave ~ '^[A-Za-z0-9._:-]{1,80}$'),
    CONSTRAINT idempotencia_hash_formato CHECK (corpo_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT idempotencia_status_faixa CHECK (status IS NULL OR status BETWEEN 200 AND 299),
    CONSTRAINT idempotencia_resposta_completa CHECK ((status IS NULL) = (corpo_resposta IS NULL))
);

CREATE INDEX idempotencia_criada_em_idx ON idempotencia (criada_em);

-- Para desfazer (o Flyway Community não tem down; rode à mão). Perde as respostas guardadas: um
-- retry com a mesma chave passa a gravar de novo.
-- DROP TABLE idempotencia;
