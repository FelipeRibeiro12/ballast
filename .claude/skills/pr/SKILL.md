---
name: pr
description: "Fecha uma fatia como no fluxo real de trabalho: branch, commits, push, abre o PR, espera o pipeline, revisa o diff e só então recomenda o merge. Use quando a implementação está pronta e testada localmente."
disable-model-invocation: true
---

# Abrir e fechar um PR

O fluxo de time de verdade, na ordem em que ele acontece. Nada aqui roda sem a
suíte verde localmente primeiro.

Requer o `gh` autenticado (`gh auth status`). Se não estiver, pare e avise —
não tente outro caminho.

## 1. Antes de qualquer coisa

```bash
git status                 # limpo? tem arquivo que não devia entrar?
git branch --show-current  # não está na main?
<comando de teste>         # verde?
<comando de lint>          # verde?
```

Se você está na `main`, **crie a branch antes do primeiro commit**:

```bash
git switch -c feat/nome-da-fatia
```

Padrão do nome: `feat/`, `fix/`, `refactor/`, `chore/` + descrição curta em
kebab-case.

## 2. Commits

Conventional commits, mensagem em português, no imperativo. **Um commit por
passo lógico** — e implementação e refatoração em commits separados, sempre.

```
feat: cria endpoint de criação de remessa
test: cobre remessa com data de entrega no passado
refactor: extrai validação de prazo para o domínio
```

Confira o que está indo:

```bash
git diff --staged --stat
```

Se aparecer `.env`, chave, dump, `node_modules` ou build, **pare**: corrija o
`.gitignore` antes de seguir. Segredo que entra no histórico não sai removendo
o arquivo depois — tem que rotacionar a credencial.

## 3. Push e abertura do PR

```bash
git push -u origin $(git branch --show-current)
gh pr create --fill --base main
```

O corpo do PR, escrito para quem vai revisar:

- **O que muda**, em uma ou duas frases
- **Por quê** — link para a fatia no backlog ou a issue
- **Como testar** — os passos exatos para ver funcionando
- **O que ficou de fora** e virou dívida
- **Risco** — o que pode quebrar se isso estiver errado

PR pequeno é revisado; PR de 40 arquivos é aprovado sem leitura. Se passou
disso, considere quebrar antes de pedir revisão.

## 4. Esperar o pipeline

```bash
gh pr checks --watch
```

**Pipeline vermelho não se contorna.** Não remova o teste, não marque como
`skip`, não force merge. Leia o log:

```bash
gh run view --log-failed
```

Corrija a causa, commite a correção na mesma branch e espere de novo. Se o
pipeline falha por algo fora da mudança (flaky, ambiente, cache), diga isso
explicitamente em vez de simplesmente rodar de novo até passar.

## 5. Revisão

Com o pipeline verde, delegue ao agente `reviewer`. Ele lê o diff contra a
base e devolve os achados por gravidade.

**Se o diff toca autenticação, autorização, sessão, criptografia, upload,
query crua ou dependência nova, passe também pelo agente `security`** — antes
da revisão humana, não depois. Achado crítico dele não vira dívida: ou corrige,
ou um ADR registra o risco aceito e quem aceitou.

- **Bloqueia** → corrige nesta branch, novo commit, pipeline de novo
- **Corrigir antes de mergear** → corrige, ou registra como dívida com
  justificativa explícita
- **Sugestão** → decide e segue

O `reviewer` não aprova o PR. Num time, quem aprova é uma pessoa — a revisão
automática serve para a pessoa não gastar o tempo dela no que uma máquina
pegaria.

## 6. Merge

Só com pipeline verde e achados bloqueantes resolvidos.

```bash
gh pr merge --squash --delete-branch
```

Squash quando o histórico da branch é ruído. Se os commits contam a história
da implementação — e no projeto acadêmico eles são material do artigo — use
`--merge` e preserve.

Depois:

```bash
git switch main && git pull
```

## 7. Fechar

- Atualiza o backlog: fatia entregue
- Se a forma de rodar mudou, delega ao agente `docs`
- Se virou versão, changelog

## O que garante de verdade

Esta skill é um roteiro, e roteiro é pedido, não garantia. Num repositório de
produção, o que impede código ruim de entrar na `main` é **branch protection**:
exigir pipeline verde, exigir uma aprovação humana e proibir push direto. Se o
repositório não tem isso configurado, avise — é mais importante que qualquer
passo acima.
