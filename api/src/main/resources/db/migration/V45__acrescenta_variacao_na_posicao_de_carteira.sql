-- ADR-082. Variação do valor da posição nas últimas 24 h, em centavos de dólar, como a Zerion
-- informa. Nula quando ela não informa e nas linhas lidas antes desta migration. Desfazer: ALTER
-- TABLE posicao_carteira DROP COLUMN variacao_24h_usd_centavos.
ALTER TABLE posicao_carteira ADD COLUMN variacao_24h_usd_centavos bigint;
