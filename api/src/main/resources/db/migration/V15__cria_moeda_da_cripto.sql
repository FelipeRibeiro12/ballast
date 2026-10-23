-- ADR-038. Cripto em dólar: movimento e cotação dizem a moeda do valor, e o índice ganha a PTAX.

-- Passo 1: o que a F13 gravou é reais. O DEFAULT só existe para preencher essas linhas e sai em
-- seguida: daqui em diante a aplicação sempre diz a moeda (USD). ADD COLUMN com DEFAULT constante
-- não reescreve a tabela no Postgres 11+.
ALTER TABLE movimento_cripto ADD COLUMN moeda text NOT NULL DEFAULT 'BRL';
ALTER TABLE movimento_cripto ALTER COLUMN moeda DROP DEFAULT;
ALTER TABLE movimento_cripto ADD CONSTRAINT movimento_cripto_moeda_valida
    CHECK (moeda IN ('BRL', 'USD')) NOT VALID;
ALTER TABLE movimento_cripto VALIDATE CONSTRAINT movimento_cripto_moeda_valida;

ALTER TABLE cotacao ADD COLUMN moeda text NOT NULL DEFAULT 'BRL';
ALTER TABLE cotacao ALTER COLUMN moeda DROP DEFAULT;
ALTER TABLE cotacao ADD CONSTRAINT cotacao_moeda_valida CHECK (moeda IN ('BRL', 'USD')) NOT VALID;
ALTER TABLE cotacao VALIDATE CONSTRAINT cotacao_moeda_valida;

-- Passo 2: PTAX de venda (série 1 do SGS), em reais por dólar.
ALTER TABLE indice DROP CONSTRAINT indice_serie_valida;
ALTER TABLE indice ADD CONSTRAINT indice_serie_valida
    CHECK (serie IN ('cdi', 'ipca', 'dolar')) NOT VALID;
ALTER TABLE indice VALIDATE CONSTRAINT indice_serie_valida;

-- Para desfazer (rode à mão):
-- DELETE FROM indice WHERE serie = 'dolar';
-- ALTER TABLE indice DROP CONSTRAINT indice_serie_valida;
-- ALTER TABLE indice ADD CONSTRAINT indice_serie_valida CHECK (serie IN ('cdi', 'ipca'));
-- ALTER TABLE cotacao DROP CONSTRAINT cotacao_moeda_valida;
-- ALTER TABLE movimento_cripto DROP CONSTRAINT movimento_cripto_moeda_valida;
-- e, sabendo que linhas em USD perderiam a moeda, as duas colunas moeda saem por DROP COLUMN;
-- apague a linha de flyway_schema_history da versão 15.
