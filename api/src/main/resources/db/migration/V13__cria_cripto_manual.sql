-- ADR-035. Cripto manual: conta do tipo cripto, ativo por usuário, movimento de compra e venda e
-- cotação manual. A posição não é tabela: é a soma dos movimentos (ADR-035, "Conta e posição").

-- Passo 1: conta aceita o tipo novo, como na V12. Conta cripto não tem saldo em reais, então o
-- saldo_inicial dela só pode ser 0. NOT VALID + VALIDATE separa a troca da varredura.
ALTER TABLE conta DROP CONSTRAINT conta_tipo_valido;
ALTER TABLE conta ADD CONSTRAINT conta_tipo_valido
    CHECK (tipo IN ('corrente', 'poupanca', 'dinheiro', 'cartao_credito', 'beneficio', 'cripto'))
    NOT VALID;
ALTER TABLE conta VALIDATE CONSTRAINT conta_tipo_valido;

ALTER TABLE conta ADD CONSTRAINT conta_cripto_sem_saldo
    CHECK (tipo <> 'cripto' OR saldo_inicial = 0) NOT VALID;
ALTER TABLE conta VALIDATE CONSTRAINT conta_cripto_sem_saldo;

-- Passo 2: lançamento recusado em conta cripto, sem gatilho. A FK composta só sabe exigir
-- igualdade, então "tipo diferente de cripto" vira igualdade com uma coluna booleana: conta
-- ganha aceita_lancamento (tipo <> 'cripto') e lancamento ganha uma coluna gerada constante true.
-- O custo é uma reescrita de lancamento sob lock exclusivo, como na V11 (cerca de 1 s com 400 mil
-- linhas), mais um boolean por linha e um unique pequeno em conta. A aplicação não escreve nas
-- duas colunas geradas. Como a FK também vale do lado da conta, trocar o tipo de uma conta que
-- já tem lançamento para cripto é recusado.
ALTER TABLE conta ADD COLUMN aceita_lancamento boolean
    GENERATED ALWAYS AS (tipo <> 'cripto') STORED NOT NULL;
ALTER TABLE conta ADD CONSTRAINT conta_id_usuario_aceita_lancamento_unico
    UNIQUE (id, usuario_id, aceita_lancamento);

ALTER TABLE lancamento ADD COLUMN conta_aceita_lancamento boolean
    GENERATED ALWAYS AS (true) STORED NOT NULL;
ALTER TABLE lancamento ADD CONSTRAINT lancamento_conta_aceita_lancamento
    FOREIGN KEY (conta_id, usuario_id, conta_aceita_lancamento)
    REFERENCES conta (id, usuario_id, aceita_lancamento) ON DELETE NO ACTION NOT VALID;
ALTER TABLE lancamento VALIDATE CONSTRAINT lancamento_conta_aceita_lancamento;

-- Passo 3: ativo por usuário (desvio do catálogo global de dados.md, ADR-035). Id sem DEFAULT,
-- gerado na aplicação. O unique com NULLS NOT DISTINCT impede o nativo repetido (contrato nulo).
-- O unique (id, usuario_id) é o alvo das FKs compostas de movimento_cripto e cotacao.
-- Contrato EVM gravado em minúsculas: o CHECK recusa o checksum em caixa mista, e a aplicação
-- normaliza antes de gravar. confiavel nasce false no banco; a aplicação grava true no ativo
-- digitado (ADR-035).
CREATE TABLE ativo_cripto (
    id uuid PRIMARY KEY,
    usuario_id uuid NOT NULL REFERENCES usuario (id) ON DELETE CASCADE,
    rede text NOT NULL,
    contrato text,
    simbolo text NOT NULL,
    nome text,
    decimais smallint NOT NULL,
    confiavel boolean NOT NULL DEFAULT false,
    criado_em timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ativo_cripto_rede_valida
        CHECK (rede IN ('bitcoin', 'ethereum', 'base', 'arbitrum', 'polygon', 'solana')),
    CONSTRAINT ativo_cripto_contrato_formato CHECK (
        contrato IS NULL
        OR (rede IN ('ethereum', 'base', 'arbitrum', 'polygon') AND contrato ~ '^0x[0-9a-f]{40}$')
        OR (rede = 'solana' AND contrato ~ '^[1-9A-HJ-NP-Za-km-z]{32,44}$')
    ),
    CONSTRAINT ativo_cripto_simbolo_tamanho
        CHECK (char_length(btrim(simbolo)) BETWEEN 1 AND 20 AND simbolo = btrim(simbolo)),
    CONSTRAINT ativo_cripto_nome_tamanho CHECK (nome IS NULL OR char_length(nome) <= 100),
    CONSTRAINT ativo_cripto_decimais_faixa CHECK (decimais BETWEEN 0 AND 18),
    CONSTRAINT ativo_cripto_usuario_rede_contrato_unico
        UNIQUE NULLS NOT DISTINCT (usuario_id, rede, contrato),
    CONSTRAINT ativo_cripto_id_usuario_unico UNIQUE (id, usuario_id)
);

-- Passo 4: movimento. conta_id exige conta do mesmo usuário e do tipo cripto (coluna gerada
-- constante, a técnica da V10). A quantidade ter no máximo as casas do ativo depende de outra
-- tabela e fica com a aplicação, assim como a venda não deixar a posição negativa (ADR-035).
-- NO ACTION nas FKs de dado, como na V6: apagar o usuário limpa tudo no cascade, em qualquer
-- ordem, e apagar ativo ou conta com movimento é recusado.
-- custo no mesmo teto do saldo (Number.MAX_SAFE_INTEGER em centavos).
CREATE TABLE movimento_cripto (
    id uuid PRIMARY KEY,
    usuario_id uuid NOT NULL REFERENCES usuario (id) ON DELETE CASCADE,
    conta_id uuid NOT NULL,
    ativo_id uuid NOT NULL,
    tipo text NOT NULL,
    quantidade numeric(38, 18) NOT NULL,
    data date NOT NULL,
    custo numeric(18, 2) NOT NULL,
    criado_em timestamptz NOT NULL DEFAULT now(),
    tipo_conta text GENERATED ALWAYS AS ('cripto') STORED NOT NULL,
    CONSTRAINT movimento_cripto_tipo_valido CHECK (tipo IN ('compra', 'venda')),
    CONSTRAINT movimento_cripto_quantidade_positiva CHECK (quantidade > 0),
    CONSTRAINT movimento_cripto_data_faixa
        CHECK (data BETWEEN DATE '1900-01-01' AND DATE '2199-12-31'),
    CONSTRAINT movimento_cripto_custo_faixa CHECK (custo > 0 AND custo <= 90071992547409.91),
    CONSTRAINT movimento_cripto_conta_cripto_do_usuario FOREIGN KEY (conta_id, usuario_id, tipo_conta)
        REFERENCES conta (id, usuario_id, tipo) ON DELETE NO ACTION,
    CONSTRAINT movimento_cripto_ativo_do_usuario FOREIGN KEY (ativo_id, usuario_id)
        REFERENCES ativo_cripto (id, usuario_id) ON DELETE NO ACTION
);

-- Índice das três leituras de movimento: posição de um ativo numa conta até uma data (prefixo
-- usuario_id, conta_id e faixa de data, ativo filtrado), lista da conta por data e patrimônio do
-- usuário até uma data (prefixo usuario_id mais a faixa de data). Com 205 mil movimentos no total
-- e 5 mil de um usuário, as três saíram de Parallel Seq Scan (7 a 10 ms) para Index Scan (0,06 a
-- 0,6 ms). A tabela nasce vazia, então CREATE INDEX comum não trava nada.
CREATE INDEX movimento_cripto_usuario_conta_data ON movimento_cripto (usuario_id, conta_id, data);

-- Passo 5: cotação por usuário. A fonte só aceita manual; a F14 alarga o CHECK.
-- A PK (ativo_id, momento) serve a "última cotação até um momento" lendo o índice de trás para
-- frente, então não há índice descendente a mais. Ela também cobre a FK do ativo.
CREATE TABLE cotacao (
    usuario_id uuid NOT NULL REFERENCES usuario (id) ON DELETE CASCADE,
    ativo_id uuid NOT NULL,
    momento timestamptz NOT NULL,
    preco numeric(20, 8) NOT NULL,
    fonte text NOT NULL,
    PRIMARY KEY (ativo_id, momento),
    CONSTRAINT cotacao_preco_positivo CHECK (preco > 0),
    CONSTRAINT cotacao_fonte_valida CHECK (fonte IN ('manual')),
    CONSTRAINT cotacao_ativo_do_usuario FOREIGN KEY (ativo_id, usuario_id)
        REFERENCES ativo_cripto (id, usuario_id) ON DELETE NO ACTION
);

-- Sem GRANT: o ALTER DEFAULT PRIVILEGES da V3 cobre as tabelas novas.

-- Para desfazer (o Flyway Community não tem down; rode à mão).
-- Destrutivo: apaga cotações, movimentos, ativos e as contas cripto.
-- DROP TABLE cotacao;
-- DROP TABLE movimento_cripto; (leva junto o índice)
-- DROP TABLE ativo_cripto;
-- ALTER TABLE lancamento DROP COLUMN conta_aceita_lancamento; (leva junto a FK lancamento_conta_aceita_lancamento)
-- ALTER TABLE conta DROP CONSTRAINT conta_id_usuario_aceita_lancamento_unico;
-- ALTER TABLE conta DROP COLUMN aceita_lancamento;
-- DELETE FROM conta WHERE tipo = 'cripto';
-- ALTER TABLE conta DROP CONSTRAINT conta_cripto_sem_saldo;
-- ALTER TABLE conta DROP CONSTRAINT conta_tipo_valido;
-- ALTER TABLE conta ADD CONSTRAINT conta_tipo_valido
--     CHECK (tipo IN ('corrente', 'poupanca', 'dinheiro', 'cartao_credito', 'beneficio'));
-- e apague a linha de flyway_schema_history da versão 13.
