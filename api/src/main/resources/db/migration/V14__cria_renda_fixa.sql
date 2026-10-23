-- ADR-037. Renda fixa: conta do tipo investimento, título (LCI, LCA, CDB), marcação do valor que o
-- banco mostrou e os índices públicos do Banco Central (CDI e IPCA).

-- Passo 1: conta aceita o tipo novo, sem saldo em reais, como a cripto (V13).
ALTER TABLE conta DROP CONSTRAINT conta_tipo_valido;
ALTER TABLE conta ADD CONSTRAINT conta_tipo_valido
    CHECK (tipo IN ('corrente', 'poupanca', 'dinheiro', 'cartao_credito', 'beneficio', 'cripto',
                    'investimento'))
    NOT VALID;
ALTER TABLE conta VALIDATE CONSTRAINT conta_tipo_valido;

ALTER TABLE conta ADD CONSTRAINT conta_investimento_sem_saldo
    CHECK (tipo <> 'investimento' OR saldo_inicial = 0) NOT VALID;
ALTER TABLE conta VALIDATE CONSTRAINT conta_investimento_sem_saldo;

-- Passo 2: conta investimento também não recebe lançamento, garantido como na V13 por FK
-- composta com coluna gerada. A expressão de uma coluna gerada não se troca sem DROP, então em vez
-- de recriar aceita_lancamento entra recebe_lancamento com a regra completa, e a FK nova reaproveita
-- a coluna constante lancamento.conta_aceita_lancamento da V13: lancamento não é reescrito. A FK e
-- a coluna da V13 continuam valendo e ficam redundantes (remover é DROP, registrado no backlog).
ALTER TABLE conta ADD COLUMN recebe_lancamento boolean
    GENERATED ALWAYS AS (tipo NOT IN ('cripto', 'investimento')) STORED NOT NULL;
ALTER TABLE conta ADD CONSTRAINT conta_id_usuario_recebe_lancamento_unico
    UNIQUE (id, usuario_id, recebe_lancamento);
ALTER TABLE lancamento ADD CONSTRAINT lancamento_conta_recebe_lancamento
    FOREIGN KEY (conta_id, usuario_id, conta_aceita_lancamento)
    REFERENCES conta (id, usuario_id, recebe_lancamento) ON DELETE NO ACTION NOT VALID;
ALTER TABLE lancamento VALIDATE CONSTRAINT lancamento_conta_recebe_lancamento;

-- Passo 3: título. Id gerado na aplicação. conta_id exige conta investimento do mesmo usuário
-- (coluna gerada constante, a técnica da V10). taxa: % a.a. no prefixado, % do CDI no cdi, % a.a.
-- acima do IPCA no ipca (ADR-037). Resgate é data e valor juntos.
CREATE TABLE titulo_renda_fixa (
    id uuid PRIMARY KEY,
    usuario_id uuid NOT NULL REFERENCES usuario (id) ON DELETE CASCADE,
    conta_id uuid NOT NULL,
    tipo text NOT NULL,
    nome text NOT NULL,
    valor_aplicado numeric(18, 2) NOT NULL,
    data_aplicacao date NOT NULL,
    vencimento date,
    indexador text NOT NULL,
    taxa numeric(9, 4) NOT NULL,
    data_resgate date,
    valor_resgate numeric(18, 2),
    criado_em timestamptz NOT NULL DEFAULT now(),
    tipo_conta text GENERATED ALWAYS AS ('investimento') STORED NOT NULL,
    CONSTRAINT titulo_renda_fixa_tipo_valido CHECK (tipo IN ('lci', 'lca', 'cdb')),
    CONSTRAINT titulo_renda_fixa_indexador_valido
        CHECK (indexador IN ('prefixado', 'cdi', 'ipca')),
    CONSTRAINT titulo_renda_fixa_nome_tamanho
        CHECK (char_length(nome) BETWEEN 1 AND 100 AND nome = btrim(nome)),
    CONSTRAINT titulo_renda_fixa_valor_faixa
        CHECK (valor_aplicado > 0 AND valor_aplicado <= 90071992547409.91),
    CONSTRAINT titulo_renda_fixa_data_faixa
        CHECK (data_aplicacao BETWEEN DATE '1900-01-01' AND DATE '2199-12-31'),
    CONSTRAINT titulo_renda_fixa_vencimento_depois
        CHECK (vencimento IS NULL OR (vencimento > data_aplicacao
                                      AND vencimento <= DATE '2199-12-31')),
    CONSTRAINT titulo_renda_fixa_taxa_positiva CHECK (taxa > 0),
    CONSTRAINT titulo_renda_fixa_resgate_junto
        CHECK ((data_resgate IS NULL) = (valor_resgate IS NULL)),
    CONSTRAINT titulo_renda_fixa_resgate_depois CHECK (
        data_resgate IS NULL
        OR (data_resgate >= data_aplicacao AND data_resgate <= DATE '2199-12-31')
    ),
    CONSTRAINT titulo_renda_fixa_resgate_faixa
        CHECK (valor_resgate IS NULL OR (valor_resgate >= 0
                                         AND valor_resgate <= 90071992547409.91)),
    CONSTRAINT titulo_renda_fixa_conta_investimento_do_usuario
        FOREIGN KEY (conta_id, usuario_id, tipo_conta)
        REFERENCES conta (id, usuario_id, tipo) ON DELETE NO ACTION,
    CONSTRAINT titulo_renda_fixa_id_usuario_unico UNIQUE (id, usuario_id)
);

-- Lista do usuário e o DELETE da conta (prefixo usuario_id, conta_id).
CREATE INDEX titulo_renda_fixa_usuario_conta ON titulo_renda_fixa (usuario_id, conta_id);

-- Passo 4: marcação. Uma por título e dia; sai junto com o título, que é dono dela.
CREATE TABLE marcacao_renda_fixa (
    usuario_id uuid NOT NULL REFERENCES usuario (id) ON DELETE CASCADE,
    titulo_id uuid NOT NULL,
    data date NOT NULL,
    valor numeric(18, 2) NOT NULL,
    PRIMARY KEY (titulo_id, data),
    CONSTRAINT marcacao_renda_fixa_data_faixa
        CHECK (data BETWEEN DATE '1900-01-01' AND DATE '2199-12-31'),
    CONSTRAINT marcacao_renda_fixa_valor_faixa
        CHECK (valor >= 0 AND valor <= 90071992547409.91),
    CONSTRAINT marcacao_renda_fixa_titulo_do_usuario FOREIGN KEY (titulo_id, usuario_id)
        REFERENCES titulo_renda_fixa (id, usuario_id) ON DELETE CASCADE
);

-- Passo 5: índices do Banco Central. Sem usuario_id: é dado público, igual para todos
-- (desvio registrado no ADR-037). valor é o percentual publicado (0.050788 é 0,050788% ao dia no
-- CDI; 0.45 é 0,45% ao mês no IPCA). A PK serve a busca por série e faixa de datas.
CREATE TABLE indice (
    serie text NOT NULL,
    data date NOT NULL,
    valor numeric(12, 8) NOT NULL,
    PRIMARY KEY (serie, data),
    CONSTRAINT indice_serie_valida CHECK (serie IN ('cdi', 'ipca')),
    CONSTRAINT indice_data_faixa CHECK (data BETWEEN DATE '1900-01-01' AND DATE '2199-12-31')
);

-- Sem GRANT: o ALTER DEFAULT PRIVILEGES da V3 cobre as tabelas novas.

-- Para desfazer (o Flyway Community não tem down; rode à mão). Destrutivo.
-- DROP TABLE indice;
-- DROP TABLE marcacao_renda_fixa;
-- DROP TABLE titulo_renda_fixa;
-- DELETE FROM conta WHERE tipo = 'investimento';
-- ALTER TABLE lancamento DROP CONSTRAINT lancamento_conta_recebe_lancamento;
-- ALTER TABLE conta DROP CONSTRAINT conta_id_usuario_recebe_lancamento_unico;
-- ALTER TABLE conta DROP COLUMN recebe_lancamento;
-- ALTER TABLE conta DROP CONSTRAINT conta_investimento_sem_saldo;
-- volte o CHECK de tipo da V13 e apague a linha de flyway_schema_history da versão 14.
