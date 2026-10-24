-- ADR-050. Segundo fator por TOTP, opcional por usuário. Uma linha por usuário que configurou;
-- ativado_em nulo é o segredo gerado esperando o primeiro código.
CREATE TABLE mfa (
    usuario_id uuid PRIMARY KEY REFERENCES usuario (id) ON DELETE CASCADE,
    -- nonce de 12 bytes, os 20 do segredo cifrados com AES-256-GCM e a tag de 16. A chave fica
    -- em MFA_CHAVE, fora do banco.
    segredo_cifrado bytea NOT NULL,
    ativado_em timestamptz,
    -- Passo de 30 s do último código aceito: o mesmo código não entra duas vezes.
    ultimo_passo bigint,
    -- Atraso progressivo por usuário, com a mesma regra do login (ADR-050).
    falhas int NOT NULL DEFAULT 0,
    ultima_falha_em timestamptz,
    criado_em timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT mfa_segredo_cifrado_tamanho CHECK (length(segredo_cifrado) = 48),
    CONSTRAINT mfa_falhas_nao_negativas CHECK (falhas >= 0)
);

-- Só o SHA-256 do código, como o token de sessão (ADR-015): o código é aleatório, com uns 50
-- bits. FK para mfa: desligar o MFA leva os códigos junto.
CREATE TABLE mfa_recuperacao (
    id uuid PRIMARY KEY,
    usuario_id uuid NOT NULL REFERENCES mfa (usuario_id) ON DELETE CASCADE,
    codigo_hash bytea NOT NULL,
    usado_em timestamptz,
    CONSTRAINT mfa_recuperacao_hash_sha256 CHECK (length(codigo_hash) = 32),
    CONSTRAINT mfa_recuperacao_codigo_unico UNIQUE (usuario_id, codigo_hash)
);

-- Login com senha certa e MFA ligado: o desafio vale 5 minutos e só abre uma sessão.
CREATE TABLE desafio_mfa (
    id uuid PRIMARY KEY,
    usuario_id uuid NOT NULL REFERENCES usuario (id) ON DELETE CASCADE,
    token_hash bytea NOT NULL UNIQUE,
    expira_em timestamptz NOT NULL,
    criado_em timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT desafio_mfa_token_hash_sha256 CHECK (length(token_hash) = 32)
);

CREATE INDEX desafio_mfa_usuario_id_idx ON desafio_mfa (usuario_id);

-- Para desfazer (rode à mão; destrutivo):
-- DROP TABLE desafio_mfa;
-- DROP TABLE mfa_recuperacao;
-- DROP TABLE mfa;
