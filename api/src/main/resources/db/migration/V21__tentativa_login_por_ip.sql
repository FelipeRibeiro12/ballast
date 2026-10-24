-- ADR-053. O atraso do login passa a ser por e-mail e IP: quem digita o e-mail alheio de outro
-- endereço não mantém o dono sob atraso. O ip é o RemoteAddr já normalizado (IPv6 pelo /64), no
-- máximo 64 caracteres. Linhas antigas ficam com ip vazio, que nenhum login casa, e a limpeza
-- diária apaga.
ALTER TABLE tentativa_login ADD COLUMN ip text NOT NULL DEFAULT '';
ALTER TABLE tentativa_login ALTER COLUMN ip DROP DEFAULT;
ALTER TABLE tentativa_login ADD CONSTRAINT tentativa_login_ip_tamanho CHECK (length(ip) <= 64);
ALTER TABLE tentativa_login DROP CONSTRAINT tentativa_login_pkey;
ALTER TABLE tentativa_login ADD PRIMARY KEY (email, ip);

-- Para desfazer (o Flyway Community não tem down; rode à mão). Perde o ip, e se o mesmo e-mail
-- tiver linhas de IPs diferentes a chave só por e-mail não volta: apague as duplicadas antes.
-- ALTER TABLE tentativa_login DROP CONSTRAINT tentativa_login_pkey;
-- ALTER TABLE tentativa_login ADD PRIMARY KEY (email);
-- ALTER TABLE tentativa_login DROP CONSTRAINT tentativa_login_ip_tamanho;
-- ALTER TABLE tentativa_login DROP COLUMN ip;
