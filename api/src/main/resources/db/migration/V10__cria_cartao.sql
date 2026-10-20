-- ADR-031. Conta de cartão de crédito e a tabela cartao, 1:1 com a conta.

-- Passo 1: conta aceita o tipo novo. NOT VALID + VALIDATE separa a troca (rápida) da varredura,
-- como na V7. beneficio e cripto continuam fora até a fatia que trouxer cada um.
ALTER TABLE conta DROP CONSTRAINT conta_tipo_valido;
ALTER TABLE conta ADD CONSTRAINT conta_tipo_valido
    CHECK (tipo IN ('corrente', 'poupanca', 'dinheiro', 'cartao_credito')) NOT VALID;
ALTER TABLE conta VALIDATE CONSTRAINT conta_tipo_valido;

-- Passo 2: alvos das FKs de cartao. Garantir o tipo no banco sem gatilho exige que a FK leve o
-- tipo, e FK não aceita constante; por isso cartao ganha colunas geradas constantes (abaixo) e
-- conta ganha o unique que elas apontam.
-- aceita_pagamento_cartao diz se a conta pode pagar fatura (corrente ou poupança). A coluna
-- gerada STORED reescreve conta inteira sob lock exclusivo; conta é uma tabela de poucas linhas
-- por usuário, então o custo é de milissegundos. A aplicação não escreve nela.
ALTER TABLE conta ADD COLUMN aceita_pagamento_cartao boolean
    GENERATED ALWAYS AS (tipo IN ('corrente', 'poupanca')) STORED NOT NULL;
ALTER TABLE conta ADD CONSTRAINT conta_id_usuario_tipo_unico UNIQUE (id, usuario_id, tipo);
ALTER TABLE conta ADD CONSTRAINT conta_id_usuario_aceita_pagamento_unico
    UNIQUE (id, usuario_id, aceita_pagamento_cartao);

-- Id é a própria conta_id (PK), sem DEFAULT. CASCADE em usuario_id e NO ACTION nas FKs de conta
-- pelo mesmo motivo da V6: a exclusão do usuário limpa cartao e conta no mesmo cascade, em
-- qualquer ordem.
-- limite no mesmo teto do saldo (Number.MAX_SAFE_INTEGER em centavos). O limite não bloqueia
-- lançamento, é só informação do cartão (ADR-031).
-- dia_fechamento e dia_vencimento ficam entre 1 e 31; dia maior que o do mês cai no último.
CREATE TABLE cartao (
    conta_id uuid PRIMARY KEY,
    usuario_id uuid NOT NULL REFERENCES usuario (id) ON DELETE CASCADE,
    limite numeric(18, 2) NOT NULL,
    dia_fechamento smallint NOT NULL,
    dia_vencimento smallint NOT NULL,
    conta_pagamento_id uuid NOT NULL,
    -- Colunas geradas constantes que carregam o tipo para as FKs compostas. A aplicação não
    -- escreve nelas e o INSERT não as cita.
    tipo_conta text GENERATED ALWAYS AS ('cartao_credito') STORED NOT NULL,
    pagamento_aceito boolean GENERATED ALWAYS AS (true) STORED NOT NULL,
    CONSTRAINT cartao_limite_faixa CHECK (limite > 0 AND limite <= 90071992547409.91),
    CONSTRAINT cartao_dia_fechamento_faixa CHECK (dia_fechamento BETWEEN 1 AND 31),
    CONSTRAINT cartao_dia_vencimento_faixa CHECK (dia_vencimento BETWEEN 1 AND 31),
    CONSTRAINT cartao_pagamento_em_outra_conta CHECK (conta_pagamento_id <> conta_id),
    -- Dono e tipo: só conta do mesmo usuario e do tipo cartao_credito tem linha aqui.
    CONSTRAINT cartao_conta_mesmo_usuario_e_tipo FOREIGN KEY (conta_id, usuario_id, tipo_conta)
        REFERENCES conta (id, usuario_id, tipo) ON DELETE NO ACTION,
    -- Dono e tipo da conta de pagamento: do mesmo usuario e corrente ou poupança. Como a FK
    -- também vale do lado da conta, mudar o tipo de uma conta que paga cartão é recusado.
    CONSTRAINT cartao_pagamento_mesmo_usuario_e_tipo
        FOREIGN KEY (conta_pagamento_id, usuario_id, pagamento_aceito)
        REFERENCES conta (id, usuario_id, aceita_pagamento_cartao) ON DELETE NO ACTION
);

-- O 1:1 do outro lado (conta de cartão sem linha em cartao) não é garantido: pediria gatilho
-- adiável por constraint. A aplicação cria conta e cartao na mesma transação.
-- Sem índice próprio: a busca pela conta é a PK, e as FKs de conta têm poucas linhas por usuário.
-- A lista de faturas lê lancamento, e o índice dela já existe (V6, ver docs/contracts/dados.md).

-- Sem GRANT: o ALTER DEFAULT PRIVILEGES da V3 cobre a tabela nova.

-- Para desfazer (o Flyway Community não tem down; rode à mão).
-- Destrutivo: apaga os cartões, as contas de cartão e os lançamentos delas (o CHECK antigo as recusa).
-- DROP TABLE cartao;
-- DELETE FROM lancamento WHERE conta_id IN (SELECT id FROM conta WHERE tipo = 'cartao_credito');
-- DELETE FROM conta WHERE tipo = 'cartao_credito';
-- ALTER TABLE conta DROP CONSTRAINT conta_id_usuario_aceita_pagamento_unico;
-- ALTER TABLE conta DROP CONSTRAINT conta_id_usuario_tipo_unico;
-- ALTER TABLE conta DROP COLUMN aceita_pagamento_cartao;
-- ALTER TABLE conta DROP CONSTRAINT conta_tipo_valido;
-- ALTER TABLE conta ADD CONSTRAINT conta_tipo_valido
--     CHECK (tipo IN ('corrente', 'poupanca', 'dinheiro'));
-- e apague a linha de flyway_schema_history da versão 10.
