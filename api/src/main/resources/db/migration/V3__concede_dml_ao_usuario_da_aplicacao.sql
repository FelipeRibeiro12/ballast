-- ADR-021: a aplicação conecta com um papel que só tem DML. O papel é criado fora
-- da migration (criar papel com senha exige superusuário); aqui só entram os grants.
-- ${app_user} vem do placeholder do Flyway (spring.flyway.placeholders.app_user).
--
-- Sem CREATE no schema: no Postgres 15+ o public não concede CREATE a PUBLIC.
-- Sem TRUNCATE, REFERENCES, TRIGGER nem DDL: o papel não é dono de nada.

GRANT USAGE ON SCHEMA public TO ${app_user};

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO ${app_user};

-- sequências por causa de colunas serial/identity em tabelas futuras
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO ${app_user};

-- vale para objetos criados daqui em diante pelo dono (quem roda esta migration)
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO ${app_user};
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO ${app_user};

-- Para desfazer (como dono do schema):
--   ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE ALL ON TABLES FROM <app_user>;
--   ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE ALL ON SEQUENCES FROM <app_user>;
--   REVOKE ALL ON ALL TABLES IN SCHEMA public FROM <app_user>;
--   REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM <app_user>;
--   REVOKE USAGE ON SCHEMA public FROM <app_user>;
-- e apague a linha de flyway_schema_history da versão 3.
