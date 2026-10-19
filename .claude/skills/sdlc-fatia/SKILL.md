---
name: sdlc-fatia
description: Fase 3 do SDLC — implementa uma fatia vertical do backlog, do contrato ao teste, com backend, frontend e QA em sequência. Use para cada funcionalidade depois que o walking skeleton está de pé.
disable-model-invocation: true
---

# Fase 3 — Uma fatia vertical

Uma fatia por vez. Se eu pedi a fatia 3, não adiante nada da fatia 4, mesmo que
pareça óbvio. Código adiantado polui a revisão e o histórico.

Antes de começar, leia a fatia em `docs/backlog.md` e o contrato em
`docs/contracts/api.md`. Se o contrato não cobre o que a fatia precisa, essa é
a primeira coisa a resolver — e é o tech-lead quem resolve, não o backend
sozinho.

## Ordem

Pule o passo que a fatia não precisa. Não pule a ordem.

1. **tech-lead** — confirma o escopo da fatia em uma frase, cria a branch, e
   atualiza `docs/contracts/api.md` se necessário. Se surgiu decisão de
   arquitetura, `/adr`.
2. **dba** — se a fatia mexe em dado: schema e migration primeiro. O backend
   não modela tabela.
3. **backend** — ciclo da skill `tdd-ciclo`: teste que falha → implementação
   mínima → verde → commit. **Melhoria estrutural não entra aqui**, vira
   dívida no backlog e acontece depois do merge.
4. **frontend** — consome o contrato. Nunca inventa endpoint: se falta algo,
   para e devolve ao tech-lead.
5. **qa** — testa a fatia inteira ponta a ponta, tenta quebrar nas bordas, e
   escreve o achado em `reports/qa/`. **QA recomenda, não conclui.**
6. **docs** — se a forma de rodar ou de usar mudou.
7. **`/pr`** — branch, commits, pipeline, `reviewer`, merge. Essa skill tem o
   fluxo completo.
8. **tech-lead** — decide o que entra agora e o que vira dívida registrada, e
   fecha a fatia. Só ele fecha.

## Fronteiras

Cada agente escreve só na sua faixa. Cruzou a fronteira, para e devolve ao
tech-lead. Cruzar em silêncio é o pior resultado possível: ninguém revisa o que
não sabe que mudou.

## Critério de pronto

- [ ] Existe teste que falha sem a implementação
- [ ] A suíte inteira passa, não só o teste novo
- [ ] O contrato em `docs/contracts/api.md` reflete o que foi construído
- [ ] O relatório do QA existe e foi lido
- [ ] **O pipeline do PR está verde** — é ele que libera, não opinião
- [ ] Só os achados **Bloqueia** do `reviewer` foram resolvidos
- [ ] Cada dívida tem linha no backlog, com dono e com a fatia que vai pagar

A fatia não espera o código ficar bonito. Ela espera funcionar, passar e não
ter nada que bloqueie. O resto tem endereço no backlog.

## Saída

Uma linha do que funciona agora, o que virou dívida, e qual é a próxima fatia.
