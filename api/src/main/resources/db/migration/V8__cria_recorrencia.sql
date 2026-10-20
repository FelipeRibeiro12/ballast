-- ADR-026. Regra e template da recorrência, e lancamento alargado para guardar as ocorrências.
-- Ids sem DEFAULT (UUID v7 vem da aplicação). Todas as FKs de dado são NO ACTION pelo mesmo
-- motivo da V6: apagar o usuário limpa as tabelas no mesmo cascade, em qualquer ordem.

-- Um dia do mês por regra (ADR-026). dados.md previa um array; a F6 (divisão) guarda os vários
-- dias em template_divisao, então aqui fica um escalar simples. Dia 29 a 31 em mês curto cai no
-- último dia do mês, e isso é regra da aplicação: o banco só guarda o dia pedido.
-- ocorrencias_max até 10000: cobre uma série diária de uns 27 anos e impede um número absurdo
-- de virar laço longo na materialização.
CREATE TABLE regra_recorrencia (
    id uuid PRIMARY KEY,
    usuario_id uuid NOT NULL REFERENCES usuario (id) ON DELETE CASCADE,
    frequencia text NOT NULL,
    intervalo smallint NOT NULL DEFAULT 1,
    dia_do_mes smallint,
    dia_da_semana smallint,
    inicio date NOT NULL,
    fim date,
    ocorrencias_max integer,
    CONSTRAINT regra_frequencia_valida CHECK (frequencia IN ('diaria', 'semanal', 'mensal', 'anual')),
    CONSTRAINT regra_intervalo_faixa CHECK (intervalo BETWEEN 1 AND 99),
    CONSTRAINT regra_dia_do_mes_faixa CHECK (dia_do_mes BETWEEN 1 AND 31),
    CONSTRAINT regra_dia_da_semana_faixa CHECK (dia_da_semana BETWEEN 0 AND 6),
    CONSTRAINT regra_inicio_faixa CHECK (inicio BETWEEN DATE '1900-01-01' AND DATE '2199-12-31'),
    CONSTRAINT regra_fim_faixa CHECK (fim BETWEEN inicio AND DATE '2199-12-31'),
    CONSTRAINT regra_ocorrencias_max_faixa CHECK (ocorrencias_max BETWEEN 1 AND 10000),
    -- Invariante 6 de dados.md.
    CONSTRAINT regra_fim_ou_ocorrencias_max CHECK (fim IS NULL OR ocorrencias_max IS NULL),
    -- Cada frequência usa só o campo que lhe cabe, para a regra não carregar dado que ninguém lê.
    CONSTRAINT regra_campos_conforme_frequencia CHECK (
        (frequencia = 'mensal' AND dia_do_mes IS NOT NULL AND dia_da_semana IS NULL)
        OR (frequencia = 'semanal' AND dia_da_semana IS NOT NULL AND dia_do_mes IS NULL)
        OR (frequencia IN ('diaria', 'anual') AND dia_do_mes IS NULL AND dia_da_semana IS NULL)
    ),
    -- Alvo da FK composta do template.
    CONSTRAINT regra_id_usuario_unico UNIQUE (id, usuario_id)
);

-- valor_total segue o nome de dados.md (o valor cheio, antes da divisão da F6). Mesmas faixas de
-- lancamento. Conta e categoria por FK composta: o template de um usuário não aponta para
-- recurso de outro, e a categoria tem de ser do tipo do template.
-- unique (regra_id): uma regra por template na F5.
CREATE TABLE template_lancamento (
    id uuid PRIMARY KEY,
    usuario_id uuid NOT NULL REFERENCES usuario (id) ON DELETE CASCADE,
    tipo text NOT NULL,
    titulo text NOT NULL,
    descricao text,
    categoria_id uuid NOT NULL,
    conta_id uuid NOT NULL,
    valor_total numeric(18, 2) NOT NULL,
    regra_id uuid NOT NULL,
    ativo boolean NOT NULL DEFAULT true,
    criado_em timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT template_tipo_valido CHECK (tipo IN ('receita', 'despesa')),
    CONSTRAINT template_titulo_tamanho CHECK (char_length(titulo) BETWEEN 1 AND 100),
    CONSTRAINT template_titulo_sem_espaco_nas_pontas CHECK (titulo = btrim(titulo)),
    CONSTRAINT template_descricao_tamanho CHECK (char_length(descricao) <= 500),
    CONSTRAINT template_valor_total_faixa CHECK (valor_total > 0 AND valor_total <= 90071992547409.91),
    CONSTRAINT template_regra_unica UNIQUE (regra_id),
    -- Alvo da FK composta de lancamento.
    CONSTRAINT template_id_usuario_unico UNIQUE (id, usuario_id),
    CONSTRAINT template_conta_mesmo_usuario FOREIGN KEY (conta_id, usuario_id)
        REFERENCES conta (id, usuario_id) ON DELETE NO ACTION,
    CONSTRAINT template_categoria_mesmo_usuario_e_tipo FOREIGN KEY (categoria_id, usuario_id, tipo)
        REFERENCES categoria (id, usuario_id, tipo) ON DELETE NO ACTION,
    CONSTRAINT template_regra_mesmo_usuario FOREIGN KEY (regra_id, usuario_id)
        REFERENCES regra_recorrencia (id, usuario_id) ON DELETE NO ACTION
);

-- Lista de GET /recorrencias e o cascade da exclusão do usuário (que checa as FKs de conta e
-- categoria nesta tabela). Tabela nova e vazia, sem CONCURRENTLY.
CREATE INDEX template_usuario ON template_lancamento (usuario_id);

-- lancamento. Em tabela com dado: as colunas novas entram nulas ou com DEFAULT constante, que o
-- Postgres 11+ grava só no catálogo, sem reescrever a tabela. Os CHECKs entram NOT VALID e
-- depois VALIDATE, como na V7, para a varredura não segurar o lock de escrita.
ALTER TABLE lancamento ADD COLUMN template_id uuid;
ALTER TABLE lancamento ADD COLUMN ocorrencia_data date;
ALTER TABLE lancamento ADD COLUMN desgarrado boolean NOT NULL DEFAULT false;

ALTER TABLE lancamento ADD CONSTRAINT lancamento_template_mesmo_usuario
    FOREIGN KEY (template_id, usuario_id)
    REFERENCES template_lancamento (id, usuario_id) ON DELETE NO ACTION;

-- Chave de idempotência da materialização (ADR-026): vale para toda linha com template,
-- desgarrada ou não, para editar a data de uma ocorrência não deixar a regra gerar outra no dia
-- antigo. Nulo não colide em unique comum, então lançamento avulso passa. É também o índice da
-- FK e da desativação (prefixo template_id).
ALTER TABLE lancamento ADD CONSTRAINT lancamento_template_ocorrencia_unica
    UNIQUE (template_id, ocorrencia_data);

ALTER TABLE lancamento ADD CONSTRAINT lancamento_template_e_ocorrencia_juntos
    CHECK ((template_id IS NULL) = (ocorrencia_data IS NULL)) NOT VALID;
ALTER TABLE lancamento VALIDATE CONSTRAINT lancamento_template_e_ocorrencia_juntos;

ALTER TABLE lancamento ADD CONSTRAINT lancamento_desgarrado_so_com_template
    CHECK (NOT desgarrado OR template_id IS NOT NULL) NOT VALID;
ALTER TABLE lancamento VALIDATE CONSTRAINT lancamento_desgarrado_so_com_template;

ALTER TABLE lancamento ADD CONSTRAINT lancamento_ocorrencia_data_faixa
    CHECK (ocorrencia_data BETWEEN DATE '1900-01-01' AND DATE '2199-12-31') NOT VALID;
ALTER TABLE lancamento VALIDATE CONSTRAINT lancamento_ocorrencia_data_faixa;

ALTER TABLE lancamento DROP CONSTRAINT lancamento_status_valido;
ALTER TABLE lancamento ADD CONSTRAINT lancamento_status_valido
    CHECK (status IN ('previsto', 'realizado', 'cancelado')) NOT VALID;
ALTER TABLE lancamento VALIDATE CONSTRAINT lancamento_status_valido;

-- Transferência não é prevista nem recorrente: a F4 só cria transferência já realizada.
ALTER TABLE lancamento ADD CONSTRAINT lancamento_transferencia_realizada_sem_template
    CHECK (tipo <> 'transferencia' OR (status = 'realizado' AND template_id IS NULL)) NOT VALID;
ALTER TABLE lancamento VALIDATE CONSTRAINT lancamento_transferencia_realizada_sem_template;

-- Dívida do backlog: função de gatilho sem search_path fixo. A da V7 só usa lancamento e
-- transferencia, então fixar o caminho muda só quem poderia sombrear essas tabelas. ALTER
-- FUNCTION é seguro e não toca no corpo.
ALTER FUNCTION transferencia_perna_em_dois_papeis() SET search_path = public, pg_temp;

-- Sem índice novo em lancamento: o saldo e a lista usam (usuario_id, data_caixa) e filtram o
-- status depois. Sem GRANT: o ALTER DEFAULT PRIVILEGES da V3 cobre as tabelas novas.

-- Para desfazer (o Flyway Community não tem down; rode à mão).
-- Destrutivo: apaga regras, templates e as ocorrências dos templates.
-- DELETE FROM lancamento WHERE template_id IS NOT NULL OR status <> 'realizado';
-- ALTER FUNCTION transferencia_perna_em_dois_papeis() RESET search_path;
-- ALTER TABLE lancamento DROP CONSTRAINT lancamento_transferencia_realizada_sem_template;
-- ALTER TABLE lancamento DROP CONSTRAINT lancamento_status_valido;
-- ALTER TABLE lancamento ADD CONSTRAINT lancamento_status_valido CHECK (status IN ('realizado'));
-- ALTER TABLE lancamento DROP COLUMN desgarrado, DROP COLUMN ocorrencia_data, DROP COLUMN template_id;
--   (a coluna leva junto as constraints e a FK da lancamento)
-- DROP TABLE template_lancamento;
-- DROP TABLE regra_recorrencia;
-- e apague a linha de flyway_schema_history da versão 8.
