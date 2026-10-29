# ADR-068: Visões de saldo

**Status:** aceito
**Data:** 2026-10-29
**Muda:** ADR-065 (o carrossel passa a ser configurável)

## Contexto

O carrossel de totais tinha grupos fixos no código. O dono quer montar as
próprias visões, escolher os tipos de conta de cada uma, editar e apagar.

## Decisão

- Tabela `visao_saldo` (V32): título, tipos de conta e data de criação, por
  usuário. Cada linha é um cartão azul do carrossel no Início.
- Os tipos valem os de `conta.tipo`, de 1 a 8, sem repetir. O valor do cartão
  é a soma dos saldos das contas ativas desses tipos. Cripto e investimento
  vêm do patrimônio (ADR-035, ADR-037), como antes.
- A visão padrão **Total sem cartão** nasce na primeira leitura, com todos os
  tipos menos cartão de crédito. Ela é uma linha como as outras: dá para
  renomear e trocar os tipos.
- Até 10 visões por usuário (`LIMITE_VISOES_SALDO`, ADR-055). A última visão
  não se apaga, para o carrossel nunca ficar vazio.
- Rotas: `GET`, `POST` e `PUT/DELETE /visoes-saldo/{id}`. A ordem é a de
  criação.
- A tela de Contas deixa de mostrar os totais, que ficam só no Início.

## Consequência

Quem apaga a visão padrão e depois cria outras não recebe a padrão de volta:
ela só nasce quando não existe nenhuma visão.
