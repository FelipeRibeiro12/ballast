---
name: sdlc-brainstorm
description: "Fase 0 do SDLC — lapida a ideia e decide a forma do sistema: monolito ou microsserviços, monorepo ou polirepo, o que é MVP. Use antes de qualquer requisito ou escolha de stack."
disable-model-invocation: true
---

# Fase 0 — Brainstorm

Nenhum arquivo de código, nenhuma escolha de framework. Aqui se decide **o que
vale construir** e **qual a forma do sistema**. A stack vem depois, na fase 1.

Você é o tech-lead. Seu papel aqui é **questionar**, não concordar.

## 1. Entender a ideia

Pergunte até conseguir responder, com as palavras do usuário:

- Quem usa isso e o que essa pessoa quer conseguir fazer?
- Como ela resolve esse problema hoje, sem o sistema?
- O que precisa ser verdade para isso valer a pena?
- Quantos usuários e quanto dado, na ordem de grandeza — dezenas, milhares,
  milhões? Isso muda quase toda decisão seguinte.
- Quem mantém depois de pronto?

Máximo 4 perguntas por vez. Se uma resposta não fechar, pergunte de novo. Não
preencha lacuna com suposição.

## 2. Reduzir ao MVP

Liste tudo que a ideia pede. Depois corte até sobrar **a menor coisa que já
serve para alguém**. Para cada item cortado, diga se é "depois" ou "nunca".

Se o usuário resistir ao corte, mostre o custo: cada funcionalidade a mais é
mais superfície de bug, mais teste, mais coisa para manter. Não ceda em
silêncio — mas a decisão é dele.

## 3. Decidir a forma do sistema

Esta é a decisão mais cara de reverter. Seja direto.

### Monolito ou microsserviços

**Comece monolito, quase sempre.** Microsserviço resolve problema
organizacional — times diferentes com ciclos de deploy diferentes — e cobra em
infraestrutura, observabilidade, consistência de dados e depuração
distribuída. Time pequeno com microsserviço paga o custo sem ter o problema.

Só considere separar quando houver pelo menos um destes, hoje e não no futuro:

- Times que se atrapalham no mesmo deploy
- Uma parte com perfil de carga radicalmente diferente do resto
- Exigência real de isolamento (regulatório, de segurança)

Um monolito bem modularizado por domínio se separa depois. O contrário quase
nunca acontece.

Se o usuário quer microsserviços para aprender ou para a banca, isso é um
motivo legítimo — mas registre no ADR que a razão é essa, não técnica.

### Monorepo ou repositórios separados

Ortogonal à decisão acima: dá para ter microsserviços em monorepo e monolito em
polirepo.

- **Monorepo** quando as partes mudam juntas, quando quer tipo ou contrato
  compartilhado, e quando quem mexe é a mesma pessoa. Custa ferramenta de build
  e CI que entende o grafo.
- **Separados** quando os ciclos de vida são independentes, quando cada parte
  tem dono diferente, ou quando o acesso precisa ser restrito por parte.

### Comunicação, se houver mais de uma parte

Síncrono (HTTP/gRPC) é mais simples de entender e depurar. Assíncrono (fila,
evento) desacopla e aguenta pico, ao custo de ordem, duplicata e depuração bem
mais difícil. Não use fila sem saber o que acontece quando a mensagem chega
duas vezes.

### Fronteiras

Desenhe o sistema em caixas por **domínio**, não por camada técnica. Para cada
caixa: o que ela é dona, o que expõe, do que depende.

Caixa que precisa ler direto o banco de outra caixa é sinal de que a fronteira
está errada.

## 4. Modelo de ameaça, em dez minutos

A auditoria mais barata é a que acontece antes de existir código. Responda:

- Que dado sensível o sistema guarda? Se vazar, qual o estrago?
- Quais são os perfis de usuário, e o que cada um **não** pode ver? Essa
  resposta é o desenho da autorização — descobrir depois custa uma semana.
- Qual a superfície exposta na internet?
- Que parte, se cair, para o negócio? (Isso define RTO e RPO.)
- O sistema guarda dado pessoal? Se sim, a LGPD entra no escopo e isso é
  decisão de projeto, não detalhe técnico.

Escreva em `docs/ameacas.md`. O checklist completo fica para `/seguranca`.

## 5. Registrar

Escreva ADRs com a skill `/adr` para cada decisão desta fase. No mínimo:

- ADR: forma do sistema (monolito/microsserviços) — **com a justificativa real**
- ADR: organização do repositório
- ADR: forma de comunicação, se houver mais de uma parte

Escreva também `docs/visao.md`: o problema, quem usa, o MVP, o que ficou fora,
e o desenho das caixas.

## Saída

Um resumo do que foi decidido e o que continua em aberto. Não escreva
requisito detalhado nem escolha framework — a próxima fase é
`/sdlc-discovery`.
