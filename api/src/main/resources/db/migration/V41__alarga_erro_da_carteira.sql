-- A interface precisa dizer por que a leitura falhou. Dois motivos novos: o indexador recusou a
-- chave (401 ou 403) e a cota acabou (429). Os três antigos continuam valendo.
ALTER TABLE carteira_observada DROP CONSTRAINT carteira_observada_erro_valido;
ALTER TABLE carteira_observada ADD CONSTRAINT carteira_observada_erro_valido CHECK (
    erro_ultima_sync IS NULL
    OR erro_ultima_sync IN (
        'indexador_indisponivel', 'resposta_invalida', 'sem_chave',
        'indexador_recusou', 'indexador_limite'
    )
);

-- Desfazer (só se nenhuma linha usar os motivos novos):
-- ALTER TABLE carteira_observada DROP CONSTRAINT carteira_observada_erro_valido;
-- ALTER TABLE carteira_observada ADD CONSTRAINT carteira_observada_erro_valido CHECK (
--     erro_ultima_sync IS NULL
--     OR erro_ultima_sync IN ('indexador_indisponivel', 'resposta_invalida', 'sem_chave')
-- );
