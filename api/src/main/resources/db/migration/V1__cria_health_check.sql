-- Tabela descartável do walking skeleton; será removida por uma migration nova (esta não se edita depois de aplicada).
CREATE TABLE health_check (
    id smallint PRIMARY KEY,
    mensagem text NOT NULL
);

INSERT INTO health_check (id, mensagem) VALUES (1, 'ok');

-- Para desfazer (o Flyway Community não tem down; rode à mão):
-- DROP TABLE health_check;
