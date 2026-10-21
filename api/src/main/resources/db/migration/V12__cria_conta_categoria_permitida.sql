-- ADR-034. Conta de benefício e a lista de categorias de despesa que ela aceita.

-- Passo 1: conta aceita o tipo novo. NOT VALID + VALIDATE separa a troca da varredura, como na
-- V10. cripto continua fora até a fatia que trouxer.
ALTER TABLE conta DROP CONSTRAINT conta_tipo_valido;
ALTER TABLE conta ADD CONSTRAINT conta_tipo_valido
    CHECK (tipo IN ('corrente', 'poupanca', 'dinheiro', 'cartao_credito', 'beneficio')) NOT VALID;
ALTER TABLE conta VALIDATE CONSTRAINT conta_tipo_valido;

-- Passo 2: a lista. Sem linha para a conta, qualquer categoria passa (lista vazia libera tudo).
-- As colunas geradas constantes carregam o tipo para as FKs compostas, a mesma técnica da V10,
-- então o banco recusa conta que não é benefício e categoria que não é despesa sem gatilho. A
-- aplicação não escreve nelas.
-- ON DELETE da conta é CASCADE: a lista é propriedade da conta e não faz sentido sem ela. O da
-- categoria é NO ACTION de propósito: CASCADE tiraria a categoria da lista em silêncio, e se
-- fosse a última a conta passaria a aceitar tudo. Apagar categoria listada tem de ser recusado.
-- O ON UPDATE padrão (NO ACTION) também recusa mudar o tipo de uma conta que tem lista.
-- A exclusão do usuário limpa tudo no mesmo cascade, em qualquer ordem.
CREATE TABLE conta_categoria_permitida (
    conta_id uuid NOT NULL,
    categoria_id uuid NOT NULL,
    usuario_id uuid NOT NULL REFERENCES usuario (id) ON DELETE CASCADE,
    tipo_conta text GENERATED ALWAYS AS ('beneficio') STORED NOT NULL,
    tipo_categoria text GENERATED ALWAYS AS ('despesa') STORED NOT NULL,
    PRIMARY KEY (conta_id, categoria_id),
    CONSTRAINT conta_categoria_permitida_conta_beneficio_do_usuario
        FOREIGN KEY (conta_id, usuario_id, tipo_conta)
        REFERENCES conta (id, usuario_id, tipo) ON DELETE CASCADE,
    CONSTRAINT conta_categoria_permitida_categoria_despesa_do_usuario
        FOREIGN KEY (categoria_id, usuario_id, tipo_categoria)
        REFERENCES categoria (id, usuario_id, tipo) ON DELETE NO ACTION
);

-- A busca do gatilho é por (conta_id, categoria_id) e por conta_id: a PK cobre as duas. A FK da
-- categoria não tem índice porque categoria não é apagada hoje; quem trouxer essa query cria.
-- Sem GRANT: o ALTER DEFAULT PRIVILEGES da V3 cobre a tabela nova.

-- Passo 3: o gatilho. Só despesa é restrita (ADR-034). Receita e transferência nem entram, pela
-- cláusula WHEN. O FOR SHARE na conta serializa com quem troca a lista: a aplicação troca a lista
-- segurando a conta FOR UPDATE, então o gatilho espera esse commit e a leitura seguinte, com
-- snapshot novo em READ COMMITTED, já vê a lista nova. Só trava quando a conta é benefício. Quem
-- escreve lançamento pela aplicação já segura a conta FOR UPDATE (ADR-024), e o lock mais fraco
-- é absorvido. O search_path fixo é o mesmo da V8, contra tabela sombra em pg_temp.
CREATE FUNCTION lancamento_categoria_permitida_no_beneficio() RETURNS trigger
    LANGUAGE plpgsql
    SECURITY INVOKER
    SET search_path = public, pg_temp
AS $$
BEGIN
    PERFORM 1 FROM conta
    WHERE id = NEW.conta_id AND usuario_id = NEW.usuario_id AND tipo = 'beneficio'
    FOR SHARE;
    IF NOT FOUND THEN
        RETURN NEW;
    END IF;

    IF EXISTS (SELECT 1 FROM conta_categoria_permitida WHERE conta_id = NEW.conta_id)
       AND NOT EXISTS (
            SELECT 1 FROM conta_categoria_permitida
            WHERE conta_id = NEW.conta_id AND categoria_id = NEW.categoria_id
       ) THEN
        RAISE EXCEPTION 'categoria fora da lista permitida da conta de beneficio'
            USING ERRCODE = 'check_violation',
                  CONSTRAINT = 'lancamento_categoria_permitida_no_beneficio';
    END IF;
    RETURN NEW;
END;
$$;

-- Fora do UPDATE OF ficam titulo, valor, datas e status: editar um lançamento antigo não o
-- revalida contra a lista de hoje (ADR-034).
CREATE TRIGGER lancamento_categoria_permitida_no_beneficio
    BEFORE INSERT OR UPDATE OF tipo, conta_id, categoria_id ON lancamento
    FOR EACH ROW WHEN (NEW.tipo = 'despesa')
    EXECUTE FUNCTION lancamento_categoria_permitida_no_beneficio();

-- Para desfazer (o Flyway Community não tem down; rode à mão).
-- Destrutivo: apaga as listas, as contas de benefício e os lançamentos delas (o CHECK antigo as recusa).
-- DROP TRIGGER lancamento_categoria_permitida_no_beneficio ON lancamento;
-- DROP FUNCTION lancamento_categoria_permitida_no_beneficio();
-- DROP TABLE conta_categoria_permitida;
-- DELETE FROM lancamento WHERE conta_id IN (SELECT id FROM conta WHERE tipo = 'beneficio');
-- DELETE FROM conta WHERE tipo = 'beneficio';
-- ALTER TABLE conta DROP CONSTRAINT conta_tipo_valido;
-- ALTER TABLE conta ADD CONSTRAINT conta_tipo_valido
--     CHECK (tipo IN ('corrente', 'poupanca', 'dinheiro', 'cartao_credito'));
-- e apague a linha de flyway_schema_history da versão 12.
