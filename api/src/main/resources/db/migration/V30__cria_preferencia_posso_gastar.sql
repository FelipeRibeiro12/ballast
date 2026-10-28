-- ADR-063. Até que dia do mês o Posso gastar conta. Uma linha por usuário; sem linha vale o ciclo
-- da fatura âncora. Mudar o dia não toca em lançamento nenhum: só muda a janela da próxima leitura.
CREATE TABLE preferencia_posso_gastar (
    usuario_id uuid PRIMARY KEY REFERENCES usuario (id) ON DELETE CASCADE,
    dia_limite smallint NOT NULL,
    CONSTRAINT preferencia_posso_gastar_dia CHECK (dia_limite BETWEEN 1 AND 31)
);

-- Desfazer:
-- DROP TABLE preferencia_posso_gastar;
