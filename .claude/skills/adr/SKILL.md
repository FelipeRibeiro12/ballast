---
name: adr
description: Escreve um ADR (registro de decisão de arquitetura) em docs/adr-NNN.md. Use quando uma decisão de arquitetura, biblioteca, padrão ou estratégia for tomada e precisar ficar registrada, ou quando eu pedir "escreve o ADR disso".
disable-model-invocation: true
---

# Escrever um ADR

Uma decisão de arquitetura só existe se estiver escrita. Se não for registrada
na hora, ela se perde — e sem o registro não dá para defender a escolha depois.

## Quando escrever

Sempre que houve escolha entre opções e alguém pudesse razoavelmente ter
decidido diferente. Escolha óbvia não precisa de ADR.

Se a decisão ainda não foi tomada, não escreva o ADR. Diga quais são as opções e
espere a escolha.

## Passo a passo

1. Liste os ADRs existentes em `docs/` e descubra o próximo número (`NNN` com
   três dígitos: 001, 002...).
2. Se esta decisão substitui uma anterior, **não apague o ADR antigo**. Marque o
   status dele como `substituído por ADR-NNN` e escreva o novo. Esse histórico
   mostra processo de engenharia real.
3. Escreva `docs/adr-NNN.md` no formato abaixo. Curto: 5 a 15 linhas no total.
4. Se houver um índice de ADRs no README ou no CLAUDE.md, acrescente a linha.
5. Commit separado, com `docs:`.

## Formato

```markdown
# ADR-NNN: Título da decisão

**Status:** aceito | substituído por ADR-NNN | descartado
**Data:** AAAA-MM-DD

## Contexto
O problema que exigiu a decisão.

## Decisão
O que foi decidido, em uma ou duas frases.

## Justificativa
Por quê.

## Alternativas descartadas
O que foi considerado e por que não foi escolhido.
```

Quando útil, acrescente `## Limitação reconhecida`, `## Consequência` ou
`## Em aberto`.

## O que não fazer

- Não invente a justificativa. Se você não sabe por que a escolha foi feita,
  pergunte antes de escrever.
- Não escreva "Alternativas descartadas: nenhuma". Se não havia alternativa,
  não era uma decisão e não precisa de ADR.
- Não encha de texto. ADR de 40 linhas ninguém relê.
