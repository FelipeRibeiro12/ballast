# Changelog

Fatia entregue sai do `docs/backlog.md` e vira linha aqui.

## Não lançado

- F0 — Walking skeleton: `GET /healthz` tocando o Postgres, tela que mostra o resultado, CI com dois workflows.
- F0.5 — Contrato OpenAPI: `api/openapi.yaml` como fonte da verdade, teste de aderência no Go, tipos TS gerados no build e gate no CI contra divergência.
- Migração do backend para Java 25 e Spring Boot 4 (ADR-016 a 019): `/healthz` em Spring, migrations V1 e V2 no Flyway, spec OpenAPI gerada do código, CI em Maven. A F1 em Go foi descartada e será reimplementada em Java. Quem tinha o banco da versão Go encontra o passo a passo no README.
- F1 (cadastro e login): `POST /auth/login`, `POST /auth/logout` e `GET /auth/eu`. A sessão vai no cookie `ballast_sessao` por 7 dias, sem renovação. O logout invalida a sessão no servidor.
- F1 (cadastro e login): depois de 3 falhas seguidas com o mesmo e-mail, o login espera um tempo que dobra a cada falha, até 15 minutos. Vale com ou sem conta.
- F1 (cadastro e login): tela de entrada e de saída na web. Usuário inicial criado por `make seed`. Cadastro pela API só com `REGISTRO_ABERTO=true`.
- F1 (cadastro e login): a API conecta com um papel que só tem DML, e a migração roda com outro. Dois pares novos no `.env`: `DB_USER` e `DB_MIGRATION_USER`. Quem já tem o banco local de antes precisa de `docker compose down -v`, descrito no README.
- F1.1 (tela de cadastro): formulário de cadastro no web, visível só com `VITE_REGISTRO_ABERTO=true`. Depois de criar a conta, o web já entra com ela.
- F2 (contas): criar, listar, ver e arquivar contas corrente, poupança e dinheiro, com saldo e total na tela. Valores em centavos. Banco fora passa a dar 503 em qualquer rota, e o limite de 4 KiB de corpo vale para todas.
- F3 (lançamento realizado): receita e despesa com título, valor, categoria, conta e data; tela do mês com totais, criação, edição e exclusão. O saldo da conta soma os lançamentos. Categorias nascem no cadastro (e na migration para quem já tinha conta). Lançamento com conta ou categoria de outro usuário não grava nem por erro da aplicação: o banco recusa.
- F4 (transferência): mover dinheiro entre duas contas do usuário, com as duas pernas criadas e apagadas juntas. Fica fora dos totais de receita e despesa do mês. O log de erro interno deixou de gravar o conteúdo da linha que o banco recusou.
- F5 (recorrência): receita e despesa que se repetem por dia, semana, mês ou ano, com intervalo, fim ou número de vezes. As ocorrências aparecem como previstas no mês, fora dos totais, e se confirmam, cancelam ou editam uma a uma. Toda escrita de dinheiro espera no máximo 2 s por trava e responde 503 em vez de pendurar. O log de arranque deixou de imprimir uma senha gerada pelo Spring Security.
- F6 (divisão de salário): uma recorrência mensal pode cair em vários dias do mês, por percentual ou por valor fixo. As parcelas somam sempre o valor cheio: no percentual, a última leva o centavo que sobra.
