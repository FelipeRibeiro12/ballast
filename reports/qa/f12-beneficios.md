# Relatório QA: F12 Benefícios com restrição (ADR-034)

**Data:** 2026-10-21, manhã, horário de São Paulo.
**Branch:** `feat/f12-beneficios`. O HEAD mudou durante a minha rodada: `6386c54` era o HEAD no início, e às 05:44 entrou `92555e7` (`feat: adiciona conta de benefício com categorias permitidas na API e no web`), que traz a API e o web. A primeira linha de base e a primeira rodada foram no working tree anterior ao commit, então refiz a linha de base e a rodada completa no HEAD `92555e7`. Os números abaixo são dessa rodada. Eu não commitei nada e não alterei nenhum arquivo de produção. Web não testado (outro agente).
**Escopo:** conta de benefício e lista de categorias permitidas, regra de despesa, job de recorrência, transferência, compra parcelada, cartão de crédito, saldo projetado, corrida, banco fora, trava de conta, log e permissão. Tudo contra Postgres 17 real.
**Ambiente:** Postgres 17 descartável `ballast-qa-f12` em `127.0.0.1:55442`, banco `ballast_qa`, com `track_commit_timestamp=on` (só nesse container, para medir a ordem de commit das gravações). Papel `qa_dono` para migração e preparo, papel `ballast_app` só com LOGIN e sem superusuário. API em `127.0.0.1:18091`, a partir do jar do `verify` do HEAD, com `REGISTRO_ABERTO=true` e cron do job em `0/20 * * * * *` (acelerado de 03:00). Não usei o banco do compose do dono nem a API da porta 8080. Não li nem imprimi nenhum `.env`. Senhas de teste ficaram num arquivo de permissão 600 fora do repositório, sem impressão. Container e API derrubados ao fim.
**Roteiro:** `/private/tmp/claude-501/-Users-mac-Programacao-meuApp-ballast/bcb0c83d-11b9-4c85-b26a-12e7b2a0a9ca/scratchpad/f12/qa_f12.py`, Python stdlib, fora do repositório. O oráculo foi escrito a partir de `docs/contracts/api.md`, do ADR-034 e de `docs/contracts/dados.md`.

**Preparo que sai do caminho normal da API (registrado para o tech-lead):**
- A API não tem rota de criação de categoria. Inseri 60 categorias extras de despesa para o usuário A direto no banco de QA, como dono.
- Para simular o dia seguinte no job, apaguei no banco de QA as ocorrências futuras de dois templates (depois de 2026-11-30). Assim o job tinha o que materializar.

## Linha de base

| Comando | Resultado |
|---|---|
| `cd api && ./mvnw -B verify` no HEAD `92555e7`, antes do roteiro | BUILD SUCCESS. 1223 testes, 0 falhas, 0 erros, 0 ignorados |

A mesma suíte no working tree anterior ao commit também deu 1223 e BUILD SUCCESS.

## Resumo

- **Achados:** 0 Bloqueia, 0 Dívida, 2 Sugestão.
- **Checagens:** 150 na rodada final, 150 PASS e 0 FAIL.
- **Travam primeiro:** nenhum.
- **Recomendação:** **liberar.** Registrar S1 e S2 no backlog, ou resolver S1 com uma linha no mesmo PR de docs. A decisão é do tech-lead. Ressalva: se o tech-lead ler a invariante 4 de `dados.md` como garantia literal do banco, S1 passa a ser contrato violado e a recomendação vira segurar.

## Achados

### Bloqueia

Nenhum.

### Dívida

Nenhuma.

### Sugestão

**S1. `dados.md` diz que o banco garante a invariante 4, mas o gatilho só checa na escrita.**

- **O que acontece:** a seção "Invariantes que o banco garante", item 4, de `docs/contracts/dados.md`, diz "Despesa em conta de benefício com lista só com categoria permitida". O gatilho dispara em `INSERT` e em `UPDATE OF tipo, conta_id, categoria_id`. Por isso, depois que a lista encolhe, uma despesa já gravada com categoria fora da lista continua no banco. Isso é o que o ADR-034 decide ("mudar a lista não mexe em lançamento já gravado"), e o código faz exatamente isso. Quem está errado é o texto do modelo, que descreve mais do que o banco garante.
- **Como reproduzir:**
  1. `POST /contas` com `tipo` `beneficio` e `categoriasPermitidas` `[D1, D2]`, D1 e D2 despesas do usuário. Esperado 201.
  2. `POST /lancamentos` com despesa de 100 e categoria D2 nessa conta. Esperado 201.
  3. `PUT /contas/{id}/categorias-permitidas` com `{"categoriaIds": [D1]}`. Esperado 200.
  4. `SELECT count(*) FROM lancamento WHERE conta_id = '<id>' AND categoria_id = '<D2>'`. Observado: 1.
  Na rodada final, a contagem crua de despesas fora da lista atual é 103, em várias contas, todas gravadas antes de a lista encolher. Nenhuma foi gravada depois da última troca (oráculo de ordem de commit: 0).
- **O que deveria acontecer:** a seção dizer o alcance real, "vale para escrita nova e edição; lançamento gravado antes da troca permanece (ADR-034)". Se a intenção for a invariante literal, a troca de lista teria de revalidar as linhas existentes, o que o ADR descarta.
- **Gravidade:** Sugestão. Não é quebra de dado nem de permissão, e o comportamento segue o ADR. O risco é de leitura: quem lê o contrato espera que o estado nunca tenha essa linha.

**S2. Conta de benefício arquivada aceita troca da lista.**

- **O que acontece:** `PATCH /contas/{id}` com `{"arquivada": true}` responde 200. Em seguida, `PUT /contas/{id}/categorias-permitidas` com `{"categoriaIds": [D2]}` responde 200 e troca a lista. Lançamento novo nessa conta continua recusado, com 400 `conta arquivada`, como deve.
- **Como reproduzir:** `POST /contas` benefício com `[D1]`; `PATCH` com `arquivada: true`; `PUT` com `[D2]`. Observado: 200 nos dois.
- **O que deveria acontecer:** o contrato não diz nada sobre lista em conta arquivada. Escolher: 400 `conta arquivada`, como lançamento, ou aceitar e escrever no contrato que a lista se edita em conta arquivada.
- **Gravidade:** Sugestão. Não afeta dinheiro, porque a conta não recebe lançamento novo. Incomoda pela ambiguidade do contrato.

### Observações sem gravidade

- **O1.** Não é achado: a primeira rodada deu 8 FAIL, e todos vieram de erro do meu roteiro (regra mensal sem `diaDoMes`, nome de coluna `conta_saida_id` em `transferencia`, PUT sem corpo enviado sem `Content-Type`, uma checagem sem efeito e um oráculo que contava linhas gravadas antes da troca). Corrigi e refiz do zero no HEAD. Nenhum desses FAIL é defeito do produto.
- **O2.** A corrida da primeira versão não colidia de verdade: os 20 POSTs terminaram antes da troca. Acrescentei uma corrida de oito rodadas, em que a regra recusa D1 depois da troca. Passou.

## Checagens

Contagem por seção na rodada final (HEAD `92555e7`). Todas PASS.

- **Setup (4):** cadastro e login de A e B, categorias e contas-base.
- **Conta (42):** POST com lista, sem campo e com `[]`. Lista com receita, categoria de B, malformada, repetida com caixa diferente, 50 válidos (201), 51 válidos e 51 inexistentes (400 de limite), 50 inexistentes (400 de categoria, sem confundir com limite). Corrente, poupança e dinheiro com lista ou com `[]` (400). `GET /contas` com `categoriasPermitidas` nulo fora de benefício e `[]` sem lista. PUT troca, `[]` libera, volta a lista, 50 válidos, id em maiúscula normalizado. PUT em corrente (400), de B sobre conta de A (404), conta inexistente (404), id malformado (404), campo faltando (400), campo extra (400), string (400), repetida (400), inexistente (400), de B (400), receita (400), 51 (400).
- **Regra (19):** despesa com categoria fora (400, nada gravado) e dentro (201). Receita com qualquer categoria (201), receita com categoria de despesa (400). PUT trocando categoria para fora (400, banco não mudou). PUT movendo despesa para benefício com categoria fora (400) e dentro (200). Benefício para corrente com categoria fora (200). Receita virando despesa com categoria fora (400). Lista vazia libera. Confirmar prevista com ajuste de valor depois de a categoria sair da lista (200, categoria mantida). PUT da mesma despesa mantendo categoria fora (400). Recorrência com categoria fora (400, sem regra nem template gravados).
- **Job (7):** dois templates, um com categoria fora da lista. Depois de simular o dia seguinte, o template com categoria fora ficou sem ocorrência nova (0), e o outro materializou na mesma rodada (11 de 11). Warn no log sem título. Ao devolver a categoria à lista, o template voltou a materializar (11 de 11). Rodadas seguidas não duplicaram ocorrência.
- **Burla (13):** PUT movendo conta e categoria juntas para fora (400) e para dentro (200). PUT de despesa antiga mantendo categoria fora (400). Antiga para corrente (200) e de volta ao benefício (400). Categoria em caixa alta aceita. Título e valor marcados recusados e aceitos. Permissão: B lança na conta de A (400 `conta não encontrada`), B usa categoria de A (400), B edita lançamento de A (404), B lê conta de A (404). Nenhuma despesa gravada depois da troca ficou fora da lista.
- **Transferência e outros (8):** quatro combinações de transferência com benefício (400, nada gravado). Transferência de B sobre conta de A (400 `conta não encontrada`). Compra parcelada em benefício (400). Cartão com pagamento em benefício (400). Recarga em benefício como receita (201).
- **Projeção (6):** sem `contaId` responde 200 e exclui benefício: o saldo atual é igual à soma das contas ativas que não são de benefício. Com `contaId` do benefício, projeta a conta dele (82550, igual à linha em `GET /contas`). Conta inexistente (404). Conta de benefício de A por B (404).
- **Corrida (6):** 20 POSTs em paralelo com a troca: 200 no PUT, só 201 nos POSTs, 10 D1 aceitos e nenhum gravado depois do commit da troca. Dois PUTs simultâneos, dez vezes: o estado final sempre é um dos dois, nunca mistura. Move entre contas em paralelo com troca, dez vezes: nenhum D2 gravado depois da troca.
- **Corrida reforçada (3):** oito rodadas de 20 POSTs com a troca no meio. 39 D1 aceitos e 41 recusados por categoria, e nenhum D1 gravado depois do commit da troca. Os PUTs responderam 200.
- **Cruzada (4):** 180 operações ao mesmo tempo (moves nos dois sentidos, trocas de lista e POSTs), nenhuma 500, nenhum deadlock no log, nenhum D2 gravado depois da troca final, e nenhum move com D1 recusado por categoria.
- **Banco direto (8):** cada comando isolado e revertido. Lista em corrente, categoria de receita na lista, apagar categoria listada, e mudar tipo de conta com lista: todos recusados pela FK. INSERT direto de despesa fora da lista e UPDATE de categoria para fora: recusados pelo gatilho, com o nome da constraint. UPDATE só de título passa, como o ADR-034 diz. Receita em benefício com lista passa.
- **Entradas inválidas (10):** `[null]`, `[""]`, id com espaço nas pontas, objeto no lugar da lista, `categoriasPermitidas` nulo em corrente (201, nulo é campo ausente) e em benefício (201 com `[]`). PUT com `categoriaIds` nulo, `[null]` e número. Despesa sem categoria em benefício. Todas 400 ou 201 como previsto, nenhuma 500.
- **Banco fora (5):** banco pausado. PUT da lista 503 em 2,03 s, POST de lançamento 503 em 2,01 s, corpo `{"erro":"serviço indisponível"}`. Depois de descongelar, PUT volta a 200.
- **Trava de conta (9):** outra sessão segura a conta de benefício `FOR UPDATE` por 8 s. PUT da lista 503 em 2,03 s, POST de lançamento 503 em 2,02 s. `GET /contas/{id}` respondeu em 0,01 s, sem esperar. Depois de liberada, POST 201 e PUT 200. Nada gravado pela metade.
- **Log (5):** `api.log` sem o título marcado, sem o valor marcado, sem o e-mail de teste e sem a senha de teste. O warn do job (8 ocorrências de "recorrência pulada") traz só `usuario_id` e `template_id`.
- **Estado final do banco:** nenhuma lista órfã (0), nenhuma transferência em benefício (0), nenhuma despesa gravada depois da última troca fora da lista (0). A contagem crua de 103 linhas fora da lista atual é o caso de S1, não de defeito.

## O que passou

- Linha de base verde no HEAD: 1223 testes.
- Conta de benefício com e sem lista, `[]`, 50 e 51 itens, repetição com caixa diferente, malformado, receita, categoria de B. Nenhuma 500.
- CRUD da lista: troca, liberar, recusas de tipo, de dono e de forma, e 404 sem vazar a existência da conta.
- Regra de despesa em POST, PUT, confirmação com ajuste e recorrência. Receita fica livre. Categoria de despesa é checada pelo tipo.
- Job: pula o template cuja categoria saiu, materializa os outros na mesma rodada, volta a materializar quando a categoria volta, e não duplica.
- Transferência, compra parcelada e cartão recusam benefício com a mensagem do contrato, sem gravar nada.
- Projeção: sem `contaId` exclui benefício, e com `contaId` projeta a conta pedida.
- Corrida: nenhuma escrita de despesa fora da lista ficou depois do commit da troca, em 8 + 20 + 10 + 10 rodadas, e a lista nunca mistura.
- Banco fora e trava de conta devolvem 503 em cerca de 2 s, sem gravar pela metade.
- Permissão: usuário B não alcança nada da conta de benefício de A, nem por lançamento, nem por lista, nem por transferência, nem por projeção.
- Log: sem título, valor, e-mail ou senha.

## Não testado

- Web: outro agente.
- Job concorrendo com troca de lista. O gatilho cobre por construção, mas não medi a ordem de commit do job, então isso fica como lacuna.
- Corpo acima de 4 KiB (413) nos endpoints novos.
- Criação de categoria pela API: a rota não existe, e o teste usou insert direto no banco de QA.

## Como reproduzir

```
cd /Users/mac/Programacao/meuApp/ballast/api && ./mvnw -B verify
# ambiente: scratchpad/f12/reset.sh (container, papéis e API na 18091)
python3 /private/tmp/claude-501/-Users-mac-Programacao-meuApp-ballast/bcb0c83d-11b9-4c85-b26a-12e7b2a0a9ca/scratchpad/f12/qa_f12.py
```

O roteiro precisa de banco limpo, porque o nome das contas é único por usuário. Por isso o `reset.sh` vem antes de cada rodada.
