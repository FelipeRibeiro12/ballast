-- A V3 concedeu DML em ALL TABLES, o que incluiu flyway_schema_history. Com isso uma
-- injeção de SQL poderia reescrever o histórico de migrations. A aplicação não precisa dela.
-- O ALTER DEFAULT PRIVILEGES da V3 não reconcede nada aqui: só vale para tabelas criadas depois.

REVOKE ALL ON flyway_schema_history FROM ${app_user};

-- Para desfazer (como dono do schema):
--   GRANT SELECT, INSERT, UPDATE, DELETE ON flyway_schema_history TO <app_user>;
-- e apague a linha de flyway_schema_history da versão 4.
