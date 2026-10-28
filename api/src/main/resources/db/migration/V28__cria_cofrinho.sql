-- ADR-061. Cofrinho: conta de liquidez diária que rende uma parte do CDI. O percentual fica na conta
-- e só o cofrinho tem. Nulo no cofrinho é "ainda sem percentual": a conta existe, o rendimento não
-- é estimado.
ALTER TABLE conta DROP CONSTRAINT conta_tipo_valido;
ALTER TABLE conta ADD CONSTRAINT conta_tipo_valido
    CHECK (tipo IN ('corrente', 'poupanca', 'dinheiro', 'cartao_credito', 'beneficio', 'cripto',
                    'investimento', 'cofrinho'))
    NOT VALID;
ALTER TABLE conta VALIDATE CONSTRAINT conta_tipo_valido;

ALTER TABLE conta ADD COLUMN percentual_cdi numeric(6, 2);
ALTER TABLE conta ADD CONSTRAINT conta_percentual_cdi_so_no_cofrinho
    CHECK (percentual_cdi IS NULL OR (tipo = 'cofrinho' AND percentual_cdi > 0 AND percentual_cdi <= 1000))
    NOT VALID;
ALTER TABLE conta VALIDATE CONSTRAINT conta_percentual_cdi_so_no_cofrinho;

-- Desfazer:
-- ALTER TABLE conta DROP CONSTRAINT conta_percentual_cdi_so_no_cofrinho;
-- ALTER TABLE conta DROP COLUMN percentual_cdi;
-- (e recriar conta_tipo_valido sem 'cofrinho', depois de converter as contas desse tipo)
