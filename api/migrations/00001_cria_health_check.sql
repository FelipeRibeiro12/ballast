-- +goose Up
-- Tabela descartável do walking skeleton; será removida por uma migration nova (esta não se edita depois de aplicada).
CREATE TABLE health_check (
    id smallint PRIMARY KEY,
    mensagem text NOT NULL
);

INSERT INTO health_check (id, mensagem) VALUES (1, 'ok');

-- +goose Down
DROP TABLE health_check;
