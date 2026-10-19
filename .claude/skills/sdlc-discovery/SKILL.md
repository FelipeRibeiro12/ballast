---
name: sdlc-discovery
description: Fase 1 do SDLC — fecha requisitos, stack e contratos antes de escrever qualquer código. Use no início de um projeto novo.
disable-model-invocation: true
---

# Fase 1 — Discovery

Você é o tech-lead nesta fase. **Nenhum código é escrito aqui.** A saída são
arquivos em `docs/` que as fases seguintes leem.

A forma do sistema — monolito ou microsserviços, monorepo ou separado — já foi
decidida em `/sdlc-brainstorm` e está nos ADRs. Se não foi, pare: essa decisão
vem antes da stack, não depois.

Se algo essencial não estiver definido, **pergunte** — no máximo 4 perguntas por
vez, e só as que mudam o que vai ser construído. Não invente requisito.

## Passos

### 1. Requisitos → `docs/requisitos.md`

- O problema em um parágrafo. Quem usa e para quê.
- Funcionalidades em uma lista, cada uma em uma frase, ordenadas por
  dependência (o que precisa existir antes do quê).
- **Fora de escopo**: o que este projeto não vai resolver. Essa seção evita
  discussão depois.
- Restrições reais: prazo, custo, plataforma, integração obrigatória.

### 2. Stack → ADR

Escolha backend, frontend, banco, CI e hospedagem. Para cada escolha que tinha
alternativa razoável, escreva um ADR com a skill `/adr`.

Critérios, nessa ordem: o que eu já sei ou quero aprender; custo (preferir
gratuito); maturidade do ferramental; tamanho da comunidade. Não escolha
tecnologia por ser nova.

### 3. Contrato de API → `docs/contracts/api.md`

Endpoints com método, caminho, formato de entrada e de saída, e os erros
possíveis. Mesmo que rudimentar. **Este arquivo é a fronteira entre backend e
frontend**: o frontend nunca inventa endpoint, e o backend nunca muda o formato
sem atualizar aqui.

### 4. Modelo de dados → `docs/contracts/dados.md`

Entidades, campos, tipos e relações. Sem SQL ainda.

### 5. Fatias → `docs/backlog.md`

Quebre as funcionalidades em fatias verticais pequenas — cada fatia atravessa a
stack inteira e entrega algo observável. Ordene por dependência. A primeira
fatia é sempre o walking skeleton.

Fatia boa cabe em uma sessão de trabalho. Se não couber, quebre de novo.

## Saída

Termine listando os arquivos criados e qual é a próxima fatia. Não comece a
implementar: a fase seguinte é `/bootstrap`, que cria o repositório na stack
escolhida, e depois `/sdlc-skeleton`.

Copie também a rule da stack escolhida (`stacks/<stack>.md` do kit) para
`.claude/rules/` do projeto — é o que carrega a convenção de linguagem só
quando um arquivo daquele tipo é lido.
