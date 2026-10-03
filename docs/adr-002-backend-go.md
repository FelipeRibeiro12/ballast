# ADR-002: Go no backend

**Status:** aceito
**Data:** 2026-10-03

## Contexto

Candidatos considerados: Go, TypeScript com Nest, Java com Spring. Todos
conhecidos pelo autor em algum grau. O objetivo declarado do projeto inclui
construir repertório para vagas de backend com afinidade a blockchain.

## Decisão

Go, com a biblioteca padrão como base, `chi` para roteamento quando o
roteador da stdlib ficar insuficiente, e `shopspring/decimal` para valor de
cripto.

## Justificativa

O domínio é aritmética, data e dinheiro. Não pede framework; pede
explicitude — tratamento de erro como valor e ausência de comportamento
implícito ajudam num código onde um erro silencioso significa saldo errado.

Go é a linguagem predominante na infraestrutura do ecossistema de blockchain,
que é a direção de carreira pretendida. Um app de finanças com posição em
cripto escrito em Go é coerente com esse objetivo.

Spring tem o melhor suporte nativo a dinheiro (`BigDecimal`) e data
(`java.time`), e é o mais demandado no mercado brasileiro em geral — mas a
iteração solo é mais lenta e o objetivo declarado não é esse. Nest seria o
mais rápido para entregar e daria tipo compartilhado com o frontend, mas
JavaScript não tem decimal nativo, o que é um atrito permanente no domínio
mais sensível deste projeto.

## Alternativas descartadas

- **TypeScript/Nest** — mais rápido de entregar e tipo compartilhado com o
  front, mas decimal em JS exige biblioteca e disciplina em todo cálculo.
- **Java/Spring** — melhor para o domínio e para o mercado generalista;
  descartado por não atender ao objetivo de carreira declarado e por iteração
  mais lenta.

## Consequência

Perde-se o tipo compartilhado com o frontend. Mitigação: especificação OpenAPI
como fonte da verdade, com cliente TypeScript gerado no build do frontend.
Isso precisa estar no pipeline desde o bootstrap — se virar passo manual, o
contrato e o cliente divergem.

## Limitação reconhecida

Bibliotecas de recorrência de data em Go são mais magras que nas outras
plataformas. A regra de recorrência deste projeto é implementada à mão
(ADR pendente se virar complexa demais), o que exige teste de borda cuidadoso:
dia 31 em fevereiro, ano bissexto, mudança de horário.
