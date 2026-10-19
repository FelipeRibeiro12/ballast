-- Ids sem DEFAULT: o UUID v7 é gerado na aplicação (Postgres 17 não tem uuidv7()).
-- mfa_segredo fica de fora; entra por migration própria na F20.
CREATE TABLE usuario (
    id uuid PRIMARY KEY,
    email text NOT NULL UNIQUE,
    senha_hash text NOT NULL,
    nome text,
    criado_em timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT usuario_email_minusculo CHECK (email = lower(email)),
    CONSTRAINT usuario_email_tamanho CHECK (char_length(email) <= 254),
    CONSTRAINT usuario_nome_tamanho CHECK (char_length(nome) <= 100)
);

-- Sessão não faz sentido sem o usuário, por isso CASCADE.
CREATE TABLE sessao (
    id uuid PRIMARY KEY,
    usuario_id uuid NOT NULL REFERENCES usuario (id) ON DELETE CASCADE,
    token_hash bytea NOT NULL UNIQUE,
    criada_em timestamptz NOT NULL DEFAULT now(),
    expira_em timestamptz NOT NULL,
    revogada_em timestamptz,
    CONSTRAINT sessao_token_hash_sha256 CHECK (length(token_hash) = 32)
);

CREATE INDEX sessao_usuario_id_idx ON sessao (usuario_id);

-- Sem FK para usuario de propósito: a chave é o e-mail digitado, para não
-- revelar se a conta existe.
CREATE TABLE tentativa_login (
    email text PRIMARY KEY,
    falhas int NOT NULL,
    ultima_falha_em timestamptz NOT NULL,
    CONSTRAINT tentativa_login_email_minusculo CHECK (email = lower(email)),
    CONSTRAINT tentativa_login_falhas_nao_negativas CHECK (falhas >= 0)
);

-- Para desfazer (o Flyway Community não tem down; rode à mão).
-- Destrutivo: apaga os dados das três tabelas. Ordem inversa por causa da FK.
-- DROP TABLE tentativa_login;
-- DROP TABLE sessao;
-- DROP TABLE usuario;
