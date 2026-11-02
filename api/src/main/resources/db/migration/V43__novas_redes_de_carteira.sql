-- ADR-080. Arbitrum, Polygon, Avalanche, Robinhood Chain e Bitcoin entram na leitura de carteira.
-- Os checks são recriados com NOT VALID e validados em seguida, como na V36.

ALTER TABLE ativo_cripto DROP CONSTRAINT ativo_cripto_rede_valida;
ALTER TABLE ativo_cripto ADD CONSTRAINT ativo_cripto_rede_valida CHECK (
    rede IN ('bitcoin', 'ethereum', 'base', 'arbitrum', 'polygon', 'avalanche', 'robinhood', 'solana')
) NOT VALID;
ALTER TABLE ativo_cripto VALIDATE CONSTRAINT ativo_cripto_rede_valida;

ALTER TABLE ativo_cripto DROP CONSTRAINT ativo_cripto_contrato_formato;
ALTER TABLE ativo_cripto ADD CONSTRAINT ativo_cripto_contrato_formato CHECK (
    contrato IS NULL
    OR (rede IN ('ethereum', 'base', 'arbitrum', 'polygon', 'avalanche', 'robinhood')
        AND contrato ~ '^0x[0-9a-f]{40}$')
    OR (rede = 'solana' AND contrato ~ '^[1-9A-HJ-NP-Za-km-z]{32,44}$')
) NOT VALID;
ALTER TABLE ativo_cripto VALIDATE CONSTRAINT ativo_cripto_contrato_formato;

ALTER TABLE carteira_observada DROP CONSTRAINT carteira_observada_rede_valida;
ALTER TABLE carteira_observada ADD CONSTRAINT carteira_observada_rede_valida CHECK (
    rede IN ('ethereum', 'base', 'arbitrum', 'polygon', 'avalanche', 'robinhood', 'solana', 'bitcoin')
) NOT VALID;
ALTER TABLE carteira_observada VALIDATE CONSTRAINT carteira_observada_rede_valida;

-- Bitcoin: legado (1 ou 3) ou bech32 em minúsculas. O checksum é conferido na aplicação.
ALTER TABLE carteira_observada DROP CONSTRAINT carteira_observada_endereco_formato;
ALTER TABLE carteira_observada ADD CONSTRAINT carteira_observada_endereco_formato CHECK (
    (rede IN ('ethereum', 'base', 'arbitrum', 'polygon', 'avalanche', 'robinhood')
        AND endereco ~ '^0x[0-9a-f]{40}$')
    OR (rede = 'solana' AND endereco ~ '^[1-9A-HJ-NP-Za-km-z]{32,44}$')
    OR (rede = 'bitcoin'
        AND (endereco ~ '^[13][1-9A-HJ-NP-Za-km-z]{25,34}$' OR endereco ~ '^bc1[a-z0-9]{8,87}$'))
) NOT VALID;
ALTER TABLE carteira_observada VALIDATE CONSTRAINT carteira_observada_endereco_formato;
