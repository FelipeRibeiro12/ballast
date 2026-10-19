---
name: sdlc-hardening
description: "Fase 4 do SDLC — paga dívida técnica de um módulo que já funciona: SOLID, design patterns, cobertura de teste e documentação. Use quando a funcionalidade está pronta e testada, nunca antes."
disable-model-invocation: true
---

# Fase 4 — Hardening

Só entra aqui código que **já funciona e já tem teste**. Se a suíte não está
verde, você está na fase errada.

Refatoração sem rede de teste não é refatoração: é reescrita às cegas.

## Ordem

Cada passo é um commit separado, com a suíte verde no fim de cada um.

### 1. Levantar

Liste os problemas reais do módulo, cada um com o sintoma concreto:
duplicação (onde e quantas vezes), função longa demais (quantas linhas),
nome que mente, responsabilidade misturada, teste faltando numa borda.

**Mostre a lista antes de mexer em qualquer coisa** e espere resposta. Não
aplique a lista inteira de uma vez.

### 2. Nomes e extração

O mais barato primeiro: renomear, extrair função, remover duplicação óbvia.
Isso resolve a maioria dos achados e não precisa de pattern nenhum.

### 3. SOLID, onde fizer diferença

- **S** — a classe tem um motivo para mudar? Se ela muda por causa de regra de
  negócio *e* de formato de saída, separe.
- **O** — só se existe variação real. Ponto de extensão inventado é
  complexidade sem cliente.
- **L** — subclasse que lança "não suportado" está errada.
- **I** — interface que ninguém implementa inteira é grande demais.
- **D** — injete dependência quando o teste precisa de dublê, ou quando existem
  duas implementações. Não antes.

Aplicar princípio onde não há dor deixa o código pior. Se você não consegue
nomear a dor, não aplique.

### 4. Design patterns

**Regra das três**: duplicação vira pattern na terceira aparição. Pattern que
você não explica em uma frase não entra. Diga qual pattern, por que este e não
outro, e o que ele resolve — antes de aplicar.

### 5. Cobertura

Cubra o que a fase de implementação deixou passar: bordas, erro, concorrência,
entrada malformada. Teste novo aqui não muda comportamento — se mudar, era bug,
e isso vira uma correção própria.

### 6. Documentação

- README atualizado se a forma de rodar mudou
- Comentários só no porquê (ver regra de comentários)
- ADR se alguma escolha estrutural foi tomada aqui
- Dívida que ficou: escreva onde ela está e o que destrava

## Nunca

- Refatorar e mudar comportamento no mesmo commit
- Relaxar teste para a refatoração passar
- Aplicar pattern "para ficar mais profissional"
