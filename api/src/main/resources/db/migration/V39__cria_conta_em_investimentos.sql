-- ADR-076. O usuário escolhe quais cofrinhos contam na tela Investimentos. Começa desmarcado: nenhum
-- cofrinho entra até a escolha. Só o cofrinho pode ficar marcado; o saldo não muda de lugar.
ALTER TABLE conta ADD COLUMN em_investimentos boolean NOT NULL DEFAULT false;
ALTER TABLE conta ADD CONSTRAINT conta_em_investimentos_so_no_cofrinho
    CHECK (NOT em_investimentos OR tipo = 'cofrinho')
    NOT VALID;
ALTER TABLE conta VALIDATE CONSTRAINT conta_em_investimentos_so_no_cofrinho;

-- Desfazer:
-- ALTER TABLE conta DROP CONSTRAINT conta_em_investimentos_so_no_cofrinho;
-- ALTER TABLE conta DROP COLUMN em_investimentos;
