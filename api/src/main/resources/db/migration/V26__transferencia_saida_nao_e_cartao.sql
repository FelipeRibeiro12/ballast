-- ADR-057. Conta de cartão nunca é a perna de saída de uma transferência: o cartão só recebe
-- (pagamento de fatura). Era regra só da aplicação; agora o banco recusa, pela mesma técnica da
-- V10 (FK composta com coluna gerada, sem gatilho).
-- pode_ser_origem diz se a conta pode sair dinheiro por transferência. STORED reescreve conta,
-- que tem poucas linhas por usuário. A aplicação não escreve na coluna.
ALTER TABLE conta ADD COLUMN pode_ser_origem boolean
    GENERATED ALWAYS AS (tipo <> 'cartao_credito') STORED NOT NULL;
ALTER TABLE conta ADD CONSTRAINT conta_id_usuario_origem_unico
    UNIQUE (id, usuario_id, pode_ser_origem);

-- Constante gerada que carrega o "true" para a FK. O INSERT de transferencia não a cita. Também
-- recusa mudar para cartão o tipo de uma conta que já tem transferência de saída, porque a FK
-- vale dos dois lados. Se já existir saída em cartão no banco, o ADD CONSTRAINT falha e a
-- migration para: a aplicação nunca gravou esse caso.
ALTER TABLE transferencia ADD COLUMN saida_pode_ser_origem boolean
    GENERATED ALWAYS AS (true) STORED NOT NULL;
ALTER TABLE transferencia ADD CONSTRAINT transferencia_saida_nao_e_cartao
    FOREIGN KEY (conta_saida_id, usuario_id, saida_pode_ser_origem)
    REFERENCES conta (id, usuario_id, pode_ser_origem) ON DELETE NO ACTION;

-- Sem índice novo: a V7 já tem as FKs de conta sem índice próprio, e o único custo é a checagem
-- ao apagar ou mudar o tipo de uma conta, rara e sobre o índice por usuario_id.

-- Para desfazer (o Flyway Community não tem down; rode à mão).
-- ALTER TABLE transferencia DROP CONSTRAINT transferencia_saida_nao_e_cartao;
-- ALTER TABLE transferencia DROP COLUMN saida_pode_ser_origem;
-- ALTER TABLE conta DROP CONSTRAINT conta_id_usuario_origem_unico;
-- ALTER TABLE conta DROP COLUMN pode_ser_origem;
