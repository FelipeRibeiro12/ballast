-- ADR-077. Cotação do USDC em reais, que converte toda a cripto de dólar para real. Dado público
-- de mercado como `indice`: sem usuario_id. Uma linha por leitura. Sem GRANT: o ALTER DEFAULT
-- PRIVILEGES da V3 cobre a tabela nova.
CREATE TABLE cotacao_usdc_brl (
    momento timestamptz PRIMARY KEY,
    preco   numeric(20, 8) NOT NULL CHECK (preco > 0),
    fonte   text NOT NULL DEFAULT 'coingecko'
);

-- Desfazer: DROP TABLE cotacao_usdc_brl;
