-- ADR-071. Carteira observada: um endereço público ligado a uma conta cripto. O saldo lido na rede
-- vira movimento_cripto sem custo, marcado como sincronizado e preso à carteira.

-- Passo 1: a carteira. Id sem DEFAULT, gerado na aplicação. A FK composta com a coluna gerada
-- constante (a técnica da V13) exige conta do mesmo usuário e do tipo cripto. NO ACTION: apagar
-- conta com carteira é recusado, e a aplicação apaga as carteiras antes (AccountDeletionQueries).
-- tentada_em é a última tentativa de leitura, boa ou ruim: o intervalo mínimo de 60 s conta dela,
-- porque sincronizada_em só anda quando a leitura dá certo.
-- endereco: EVM sempre em minúsculas (o unique não pode ver o mesmo endereço como dois), Solana
-- como veio. verificada_em fica para a F25 (prova de posse).
CREATE TABLE carteira_observada (
    id uuid PRIMARY KEY,
    usuario_id uuid NOT NULL REFERENCES usuario (id) ON DELETE CASCADE,
    conta_id uuid NOT NULL,
    rede text NOT NULL,
    endereco text NOT NULL,
    rotulo text,
    verificada_em timestamptz,
    sincronizada_em timestamptz,
    tentada_em timestamptz,
    erro_ultima_sync text,
    criado_em timestamptz NOT NULL DEFAULT now(),
    tipo_conta text GENERATED ALWAYS AS ('cripto') STORED NOT NULL,
    CONSTRAINT carteira_observada_rede_valida CHECK (rede IN ('ethereum', 'base', 'solana')),
    CONSTRAINT carteira_observada_endereco_formato CHECK (
        (rede IN ('ethereum', 'base') AND endereco ~ '^0x[0-9a-f]{40}$')
        OR (rede = 'solana' AND endereco ~ '^[1-9A-HJ-NP-Za-km-z]{32,44}$')
    ),
    CONSTRAINT carteira_observada_rotulo_tamanho
        CHECK (rotulo IS NULL OR (char_length(rotulo) BETWEEN 1 AND 60 AND rotulo = btrim(rotulo))),
    CONSTRAINT carteira_observada_erro_valido CHECK (
        erro_ultima_sync IS NULL
        OR erro_ultima_sync IN ('indexador_indisponivel', 'resposta_invalida', 'sem_chave')
    ),
    CONSTRAINT carteira_observada_usuario_rede_endereco_unico UNIQUE (usuario_id, rede, endereco),
    CONSTRAINT carteira_observada_id_usuario_unico UNIQUE (id, usuario_id),
    CONSTRAINT carteira_observada_conta_cripto_do_usuario FOREIGN KEY (conta_id, usuario_id, tipo_conta)
        REFERENCES conta (id, usuario_id, tipo) ON DELETE NO ACTION
);

-- Passo 2: movimento_cripto aceita entrada e saída (o que a leitura da rede grava), diz de onde veio
-- e a qual carteira pertence. O ADD COLUMN com DEFAULT constante não reescreve a tabela no Postgres
-- 11+, e o DEFAULT fica: movimento digitado é manual.
ALTER TABLE movimento_cripto ADD COLUMN origem text NOT NULL DEFAULT 'manual';
ALTER TABLE movimento_cripto ADD COLUMN carteira_id uuid;

ALTER TABLE movimento_cripto DROP CONSTRAINT movimento_cripto_tipo_valido;
ALTER TABLE movimento_cripto ADD CONSTRAINT movimento_cripto_tipo_valido
    CHECK (tipo IN ('compra', 'venda', 'entrada', 'saida')) NOT VALID;
ALTER TABLE movimento_cripto VALIDATE CONSTRAINT movimento_cripto_tipo_valido;

ALTER TABLE movimento_cripto ADD CONSTRAINT movimento_cripto_origem_valida
    CHECK (origem IN ('manual', 'sincronizada')) NOT VALID;
ALTER TABLE movimento_cripto VALIDATE CONSTRAINT movimento_cripto_origem_valida;

-- Manual não tem carteira, sincronizado tem. Entrada e saída só existem na leitura da rede: o
-- movimento digitado continua sendo compra ou venda.
ALTER TABLE movimento_cripto ADD CONSTRAINT movimento_cripto_origem_e_carteira
    CHECK ((origem = 'manual') = (carteira_id IS NULL)) NOT VALID;
ALTER TABLE movimento_cripto VALIDATE CONSTRAINT movimento_cripto_origem_e_carteira;
ALTER TABLE movimento_cripto ADD CONSTRAINT movimento_cripto_tipo_e_origem
    CHECK ((tipo IN ('entrada', 'saida')) = (origem = 'sincronizada')) NOT VALID;
ALTER TABLE movimento_cripto VALIDATE CONSTRAINT movimento_cripto_tipo_e_origem;

-- Apagar a carteira leva os movimentos dela. A FK inclui usuario_id, então o movimento só aponta
-- para carteira do mesmo usuário.
ALTER TABLE movimento_cripto ADD CONSTRAINT movimento_cripto_carteira_do_usuario
    FOREIGN KEY (carteira_id, usuario_id) REFERENCES carteira_observada (id, usuario_id)
    ON DELETE CASCADE NOT VALID;
ALTER TABLE movimento_cripto VALIDATE CONSTRAINT movimento_cripto_carteira_do_usuario;

-- Passo 3: custo e moeda ficam nulos juntos, e só na entrada ou saída sincronizada (custo ainda
-- não informado). O CHECK de faixa passa a dizer o nulo em voz alta; antes ele passava só porque
-- NULL no CHECK conta como verdadeiro, e a coluna era NOT NULL.
ALTER TABLE movimento_cripto ALTER COLUMN custo DROP NOT NULL;
ALTER TABLE movimento_cripto ALTER COLUMN moeda DROP NOT NULL;
ALTER TABLE movimento_cripto DROP CONSTRAINT movimento_cripto_custo_faixa;
ALTER TABLE movimento_cripto ADD CONSTRAINT movimento_cripto_custo_faixa
    CHECK (custo IS NULL OR (custo > 0 AND custo <= 90071992547409.91)) NOT VALID;
ALTER TABLE movimento_cripto VALIDATE CONSTRAINT movimento_cripto_custo_faixa;
ALTER TABLE movimento_cripto ADD CONSTRAINT movimento_cripto_custo_e_moeda
    CHECK ((custo IS NULL) = (moeda IS NULL)) NOT VALID;
ALTER TABLE movimento_cripto VALIDATE CONSTRAINT movimento_cripto_custo_e_moeda;
ALTER TABLE movimento_cripto ADD CONSTRAINT movimento_cripto_custo_nulo_so_sincronizado
    CHECK (custo IS NOT NULL OR (origem = 'sincronizada' AND tipo IN ('entrada', 'saida')))
    NOT VALID;
ALTER TABLE movimento_cripto VALIDATE CONSTRAINT movimento_cripto_custo_nulo_so_sincronizado;

-- Passo 4: a leitura compara a rede com os movimentos de uma carteira e um ativo, e o CASCADE
-- procura os movimentos da carteira. Parcial porque o manual (a maioria) não tem carteira.
CREATE INDEX movimento_cripto_carteira ON movimento_cripto (carteira_id, ativo_id)
    WHERE carteira_id IS NOT NULL;

-- Sem GRANT: o ALTER DEFAULT PRIVILEGES da V3 cobre a tabela nova.

-- Para desfazer (o Flyway Community não tem down; rode à mão).
-- Destrutivo: apaga as carteiras e os movimentos sincronizados.
-- DROP INDEX movimento_cripto_carteira;
-- DELETE FROM movimento_cripto WHERE origem = 'sincronizada';
-- ALTER TABLE movimento_cripto DROP CONSTRAINT movimento_cripto_custo_nulo_so_sincronizado;
-- ALTER TABLE movimento_cripto DROP CONSTRAINT movimento_cripto_custo_e_moeda;
-- ALTER TABLE movimento_cripto DROP CONSTRAINT movimento_cripto_custo_faixa;
-- ALTER TABLE movimento_cripto ADD CONSTRAINT movimento_cripto_custo_faixa
--     CHECK (custo > 0 AND custo <= 90071992547409.91);
-- ALTER TABLE movimento_cripto ALTER COLUMN moeda SET NOT NULL;
-- ALTER TABLE movimento_cripto ALTER COLUMN custo SET NOT NULL;
-- ALTER TABLE movimento_cripto DROP CONSTRAINT movimento_cripto_carteira_do_usuario;
-- ALTER TABLE movimento_cripto DROP CONSTRAINT movimento_cripto_tipo_e_origem;
-- ALTER TABLE movimento_cripto DROP CONSTRAINT movimento_cripto_origem_e_carteira;
-- ALTER TABLE movimento_cripto DROP CONSTRAINT movimento_cripto_origem_valida;
-- ALTER TABLE movimento_cripto DROP CONSTRAINT movimento_cripto_tipo_valido;
-- ALTER TABLE movimento_cripto ADD CONSTRAINT movimento_cripto_tipo_valido
--     CHECK (tipo IN ('compra', 'venda'));
-- ALTER TABLE movimento_cripto DROP COLUMN carteira_id;
-- ALTER TABLE movimento_cripto DROP COLUMN origem;
-- DROP TABLE carteira_observada;
-- e apague a linha de flyway_schema_history da versão 36.
-- Ativos criados pela leitura (confiavel = false) ficam: são ativos comuns do usuário.
