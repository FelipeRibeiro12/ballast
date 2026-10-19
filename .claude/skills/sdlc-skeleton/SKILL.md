---
name: sdlc-skeleton
description: Fase 2 do SDLC — walking skeleton. Inicializa o projeto com a menor fatia vertical que funciona ponta a ponta, com CI verde. Use depois que requisitos e stack estão fechados.
disable-model-invocation: true
---

# Fase 2 — Walking skeleton

Objetivo: a **menor coisa que atravessa a stack inteira e funciona**, com
pipeline verde. Não é uma funcionalidade: é o encanamento.

O alvo típico: um `GET /health` que chega no banco, consumido por uma tela
vazia, com um teste e CI rodando.

## Ordem de execução

Delegue cada passo ao agente do papel. Um por vez — cada passo só começa com o
anterior verde.

Se o repositório ainda não existe, rode `/bootstrap` antes: ele cria o
scaffold, os scripts e o CI. Esta fase assume isso pronto.

1. **devops** — confere que o pipeline roda lint + teste e que ele **falha**
   quando um teste falha. Se `/bootstrap` já fez, só valide.
2. **dba** — a migration mínima: uma tabela, um registro de exemplo.
3. **backend** — o endpoint mais trivial possível, tocando o banco, com um
   teste de integração.
4. **frontend** — uma tela que consome esse endpoint e mostra o resultado. Sem
   estilo, sem roteamento elaborado, sem componentização.
5. **qa** — quebre um teste de propósito, rode o pipeline, confirme que ele
   barra. Desfaça. Registre o resultado em `reports/qa/`.

## Proibido nesta fase

- Criar camadas, DTOs, interfaces ou repositórios "porque vamos precisar".
- Pasta vazia "por enquanto".
- Autenticação, cache, fila, Docker de produção, observabilidade.
- Qualquer design pattern.

Se você se pegar justificando uma estrutura com "depois vai ficar mais fácil",
pare: não é walking skeleton.

## Critério de pronto

Todos verdadeiros, ou a fase não acabou:

- [ ] `git clone` + um comando de setup + um comando de start funciona do zero
- [ ] O comando de teste roda e passa
- [ ] O CI roda no push e fica verde
- [ ] O CI fica vermelho quando um teste quebra
- [ ] A tela mostra dado que veio do banco
- [ ] README diz como rodar, em até 10 linhas

## Saída

Diga o que ficou funcionando, o comando para rodar, e qual é a primeira fatia
de verdade. A próxima fase é `/sdlc-fatia`.
