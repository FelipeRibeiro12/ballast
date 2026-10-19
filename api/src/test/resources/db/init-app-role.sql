-- Papel da aplicação nos testes (ADR-021). Senha descartável: o container some ao fim da suíte.
CREATE ROLE ballast_app LOGIN PASSWORD 'ballast_app_test' NOSUPERUSER NOCREATEDB NOCREATEROLE;
