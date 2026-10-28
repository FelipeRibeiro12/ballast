-- ADR-067. O Posso gastar ganha duas chaves: contar ou não os benefícios e as compras de terceiros.
-- Desligadas por padrão, que é o comportamento de antes. O dia deixa de ser obrigatório, porque dá
-- para ligar uma chave sem escolher dia (o ciclo da fatura segue valendo).
ALTER TABLE preferencia_posso_gastar ALTER COLUMN dia_limite DROP NOT NULL;
ALTER TABLE preferencia_posso_gastar ADD COLUMN incluir_beneficios boolean NOT NULL DEFAULT false;
ALTER TABLE preferencia_posso_gastar ADD COLUMN incluir_terceiros boolean NOT NULL DEFAULT false;

-- Desfazer:
-- DELETE FROM preferencia_posso_gastar WHERE dia_limite IS NULL;
-- ALTER TABLE preferencia_posso_gastar DROP COLUMN incluir_terceiros;
-- ALTER TABLE preferencia_posso_gastar DROP COLUMN incluir_beneficios;
-- ALTER TABLE preferencia_posso_gastar ALTER COLUMN dia_limite SET NOT NULL;
