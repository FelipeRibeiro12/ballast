# ADR-003: Postgres como banco

**Status:** aceito
**Data:** 2026-10-03

## Contexto

O dado é dinheiro: exige aritmética exata, restrição de integridade forte e
data com fuso. Há também um worker de cotação escrevendo de forma concorrente
com as requisições da API.

## Decisão

Postgres. Em desenvolvimento, via Docker. Em produção, plano gratuito de Neon
ou Supabase.

## Justificativa

`numeric` com escala definida dá aritmética exata para cripto (18 casas), o que
nenhum tipo inteiro de 64 bits comporta. `timestamptz` resolve fuso
corretamente. Chave estrangeira, `CHECK` e `UNIQUE` permitem que invariantes de
dinheiro sejam garantidas pelo banco e não só pela aplicação — o que importa
porque duas requisições simultâneas furam validação feita só em código.

Escrita concorrente do worker de cotação junto com a API descarta SQLite, que
serializa escrita.

## Alternativas descartadas

- **SQLite** — tentador por ser projeto solo e por simplificar o deploy;
  descartado pela escrita concorrente e pela ausência de `numeric` com a
  precisão necessária.
- **MySQL/MariaDB** — funcionaria, mas o suporte a tipo e a `CHECK` é mais
  fraco, e não há vantagem que compense.
- **MongoDB** — dado financeiro é relacional e transacional. Seria escolher a
  ferramenta errada de propósito.

## Consequência

Toda operação que toca mais de uma tabela roda em transação explícita. O schema
é dono das invariantes: saldo, unicidade de parcela e soma de divisão de
salário têm `CHECK` ou constraint, não só validação em Go.
