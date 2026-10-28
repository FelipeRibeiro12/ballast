# ADR-067: Chaves de benefícios e terceiros no Posso gastar

**Status:** aceito
**Data:** 2026-10-29
**Muda:** ADR-063 (o que entra no cálculo)

## Contexto

O Posso gastar deixava os benefícios à parte e tirava as compras de terceiros
sempre. O dono quer escolher, na engrenagem, o que entra: ligado conta, desligado
vale zero.

## Decisão

- Duas chaves na preferência do usuário (V31): `incluir_beneficios` e
  `incluir_terceiros`, as duas desligadas por padrão, que é o comportamento de
  antes. O `diaLimite` fica opcional na mesma linha.
- **Benefícios ligados**: as contas de benefício entram no escopo do menor saldo
  projetado, e a lista de benefícios à parte fica vazia para não contar duas
  vezes. Desligado, valem zero no total e aparecem à parte.
- **Terceiros ligados**: a compra de terceiro conta como gasto do dono, porque a
  fatura sai inteira da conta. Desligado, a compra vale zero. O reembolso é
  dinheiro que entra na conta e conta nos dois casos.
- `PUT /posso-gastar/configuracao` recebe `diaLimite`, `incluirBeneficios` e
  `incluirTerceiros`, grava tudo numa chamada e não altera lançamento. A resposta
  de `GET /posso-gastar` devolve as duas chaves.

## Consequência

Ligar as chaves pode derrubar o valor livre: o benefício só paga as categorias
dele, e o Posso gastar não sabe separar isso. O texto da tela avisa.
