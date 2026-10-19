# ADR-019: Spec OpenAPI gerada do código

**Status:** aceito
**Data:** 2026-10-19
**Inverte:** a mitigação do ADR-002 (spec-first)

## Contexto

O ADR-002 aceitou perder o tipo compartilhado com o frontend e registrou a
mitigação: OpenAPI como fonte da verdade, cliente TypeScript gerado dela.

A F0.5 implementou isso **spec-first**: `api/openapi.yaml` escrito à mão, o
cliente TS gerado dele, e um teste com `kin-openapi` validando que as
respostas do servidor batiam com a spec. O teste existia porque spec escrita à
mão pode divergir do servidor, e cliente tipado contra spec errada mente com
confiança.

Com Spring, o `springdoc-openapi` gera a spec dos controllers, DTOs e
anotações de validação.

## Decisão

**Code-first.** A spec é gerada do código pelo `springdoc-openapi` e commitada
em `api/openapi.yaml`. O CI falha se a spec commitada divergir da gerada.

O cliente TypeScript continua sendo gerado da spec, como hoje.

## Justificativa

**Esta decisão inverte a mitigação do ADR-002.** Spec-first torna a
divergência entre spec e servidor *detectável por teste*; gerar a spec do
código a torna *impossível por construção*. O objetivo da mitigação era a
spec não mentir, e o segundo caminho é mais forte. O teste de aderência com
`kin-openapi` deixa de ter função.

## Alternativas descartadas

- **Manter spec-first, com um validador de resposta em Java**: funcionaria,
  mas mantém a divergência possível e só a pega quando o teste cobre a rota.
- **Gerar a spec sem commitar**: o build do web precisa dela, e commitada a
  mudança de contrato aparece no diff do PR, que é onde ela tem que ser vista.

## Consequência

- O CI passa a ter dois gates em sequência: spec commitada igual à gerada do
  código, e cliente TS commitado igual ao gerado da spec. Os dois falham o
  build.
- Renomear campo de DTO muda o `openapi.yaml` no mesmo PR, e quem revisa vê.
- Perde-se escrever o contrato antes do código.
- A spec sai em OpenAPI 3.1 (a escrita à mão era 3.0.3) e sem
  `additionalProperties: false` nas respostas. O contrato de `/healthz` não
  mudou, mas a spec ficou mais permissiva que a anterior.
- Anotação de controller e DTO passa a ser contrato. Validação sem Bean
  Validation ou campo sem `@Schema` dá spec pobre, e spec pobre dá cliente TS
  pobre.

## Limitação reconhecida

Spec gerada descreve o que o código **faz**, não o que **deveria** fazer. Um
campo que vaza é documentado como se fosse intencional. Quem pega isso é a
revisão do diff do `openapi.yaml`, e é por isso que ele é commitado.
