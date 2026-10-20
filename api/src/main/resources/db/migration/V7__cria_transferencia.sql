-- ADR-025. Transferência são duas linhas de lancamento (tipo 'transferencia') ligadas por uma
-- linha de transferencia. O banco garante o par; só valor e data iguais nas duas pernas ficam
-- por conta da aplicação, porque garantir isso sem gatilho exigiria copiar valor e datas para
-- transferencia e alargar o unique de lancamento com mais três colunas.

-- Passo 1: lancamento aceita o tipo novo. NOT VALID + VALIDATE separa a troca (rápida) da
-- varredura da tabela, que não segura o lock de escrita.
ALTER TABLE lancamento DROP CONSTRAINT lancamento_tipo_valido;
ALTER TABLE lancamento ADD CONSTRAINT lancamento_tipo_valido
    CHECK (tipo IN ('receita', 'despesa', 'transferencia')) NOT VALID;
ALTER TABLE lancamento VALIDATE CONSTRAINT lancamento_tipo_valido;

-- Passo 2: categoria_id passa a aceitar nulo, só em transferência. Nulo aqui é "não se aplica".
-- A FK composta lancamento_categoria_mesmo_usuario_e_tipo continua como está: é MATCH SIMPLE
-- (o padrão), então a FK não é checada quando qualquer coluna dela é nula. A perna sem categoria
-- passa direto, e as demais seguem checadas porque o CHECK abaixo as obriga a ter categoria.
ALTER TABLE lancamento ALTER COLUMN categoria_id DROP NOT NULL;
ALTER TABLE lancamento ADD CONSTRAINT lancamento_categoria_conforme_tipo
    CHECK ((tipo = 'transferencia') = (categoria_id IS NULL)) NOT VALID;
ALTER TABLE lancamento VALIDATE CONSTRAINT lancamento_categoria_conforme_tipo;

-- Alvo das FKs de transferencia. Leva tipo e conta_id para o banco conferir que a perna é do
-- tipo transferencia e está na conta que a transferencia diz. Custa um índice a mais em
-- lancamento (4 colunas, uuid e text), pago em toda escrita de lançamento.
ALTER TABLE lancamento ADD CONSTRAINT lancamento_id_usuario_tipo_conta_unico
    UNIQUE (id, usuario_id, tipo, conta_id);

-- Mecanismo sem gatilho para as garantias de dono, tipo e contas diferentes: transferencia
-- guarda as duas contas, o CHECK exige que sejam diferentes, e as FKs compostas obrigam cada
-- perna a estar no mesmo usuario, com tipo 'transferencia' e na conta declarada.
-- tipo é coluna gerada constante: é o jeito de a FK composta carregar o valor 'transferencia'
-- sem a aplicação escrever nele (mesma técnica de categoria.nivel). Não se insere nela.
-- Todas as FKs para lancamento são NO ACTION (checam no fim do comando) pelo mesmo motivo da V6:
-- apagar o usuário apaga lancamento e transferencia no mesmo cascade.
-- Apagar a transferencia não apaga lançamento. Apagar uma perna com a ligação viva é recusado;
-- a aplicação apaga primeiro a transferencia e depois as duas pernas, numa transação.
CREATE TABLE transferencia (
    id uuid PRIMARY KEY,
    usuario_id uuid NOT NULL REFERENCES usuario (id) ON DELETE CASCADE,
    saida_id uuid NOT NULL,
    entrada_id uuid NOT NULL,
    conta_saida_id uuid NOT NULL,
    conta_entrada_id uuid NOT NULL,
    tipo text GENERATED ALWAYS AS ('transferencia') STORED NOT NULL,
    CONSTRAINT transferencia_saida_unica UNIQUE (saida_id),
    CONSTRAINT transferencia_entrada_unica UNIQUE (entrada_id),
    CONSTRAINT transferencia_pernas_diferentes CHECK (saida_id <> entrada_id),
    CONSTRAINT transferencia_contas_diferentes CHECK (conta_saida_id <> conta_entrada_id),
    CONSTRAINT transferencia_saida_perna_valida FOREIGN KEY (saida_id, usuario_id, tipo, conta_saida_id)
        REFERENCES lancamento (id, usuario_id, tipo, conta_id) ON DELETE NO ACTION,
    CONSTRAINT transferencia_entrada_perna_valida FOREIGN KEY (entrada_id, usuario_id, tipo, conta_entrada_id)
        REFERENCES lancamento (id, usuario_id, tipo, conta_id) ON DELETE NO ACTION
);

-- Os dois UNIQUE impedem um lançamento em duas saídas ou em duas entradas, mas não o mesmo
-- lançamento como saída de uma transferência e entrada de outra: um unique não enxerga duas
-- colunas ao mesmo tempo, e sem gatilho não há como fechar essa. O gatilho abaixo fecha. Ele
-- trava as pernas antes de checar (FOR UPDATE, em ordem de id para não dar deadlock) porque
-- duas transações simultâneas não se enxergam em READ COMMITTED. O lock vem antes das FKs, que
-- só rodam no fim do comando, então não cria deadlock com elas.
CREATE FUNCTION transferencia_perna_em_dois_papeis() RETURNS trigger AS $$
BEGIN
    PERFORM 1 FROM lancamento
    WHERE id IN (NEW.saida_id, NEW.entrada_id)
    ORDER BY id
    FOR UPDATE;

    IF EXISTS (
        SELECT 1 FROM transferencia
        WHERE id <> NEW.id AND (saida_id = NEW.entrada_id OR entrada_id = NEW.saida_id)
    ) THEN
        RAISE EXCEPTION 'lancamento ja e perna de outra transferencia'
            USING ERRCODE = 'unique_violation', CONSTRAINT = 'transferencia_perna_em_dois_papeis';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER transferencia_perna_em_dois_papeis
    BEFORE INSERT OR UPDATE OF saida_id, entrada_id ON transferencia
    FOR EACH ROW EXECUTE FUNCTION transferencia_perna_em_dois_papeis();

-- Índice por usuario_id: o saldo junta transferencia ao lançamento do usuário, e sem ele o
-- planejador lê a tabela inteira (todos os usuários) para montar o hash. Com 40 mil
-- transferências o saldo caiu de 6,4 ms para 2,1 ms, e o custo deixa de crescer com a tabela.
-- Também atende o cascade da exclusão do usuário. Os UNIQUE de saida_id e entrada_id já são
-- os índices dos joins da lista, e a busca por usuario_id e id usa a chave primária.
-- Tabela recém-criada e vazia, então não precisa de CONCURRENTLY.
CREATE INDEX transferencia_usuario ON transferencia (usuario_id);

-- Sem GRANT: o ALTER DEFAULT PRIVILEGES da V3 cobre tabelas criadas pelo dono.

-- Para desfazer (o Flyway Community não tem down; rode à mão).
-- Destrutivo: apaga as transferências e as pernas. Apague antes os lançamentos de tipo
-- transferencia, senão a volta do NOT NULL e do CHECK falha.
-- DROP TABLE transferencia; (leva junto o índice transferencia_usuario)
-- DROP FUNCTION transferencia_perna_em_dois_papeis();
-- ALTER TABLE lancamento DROP CONSTRAINT lancamento_id_usuario_tipo_conta_unico;
-- DELETE FROM lancamento WHERE tipo = 'transferencia';
-- ALTER TABLE lancamento DROP CONSTRAINT lancamento_categoria_conforme_tipo;
-- ALTER TABLE lancamento ALTER COLUMN categoria_id SET NOT NULL;
-- ALTER TABLE lancamento DROP CONSTRAINT lancamento_tipo_valido;
-- ALTER TABLE lancamento ADD CONSTRAINT lancamento_tipo_valido CHECK (tipo IN ('receita', 'despesa'));
-- e apague a linha de flyway_schema_history da versão 7.
