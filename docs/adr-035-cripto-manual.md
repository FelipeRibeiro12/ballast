# ADR-035: Cripto manual na F13

**Status:** aceito
**Data:** 2026-10-21

## Contexto

A F13 traz cripto digitada à mão: ativo identificado por `rede` e `contrato`,
posição em quantidade, movimentação com o custo de aquisição informado, e o
patrimônio total com data de referência. O ADR-006 fixa que a quantidade é a
verdade e o valor em reais é sempre derivado de uma cotação. A cotação
automática é a F14, que espera a escolha do provedor (CLAUDE.md, "A
completar").

`docs/contracts/dados.md` descreve `ativo_cripto` sem `usuario_id`,
`posicao_cripto` com a quantidade guardada e `cotacao` global.

## Decisão

### Ativo por usuário

- `ativo_cripto` ganha `usuario_id`, com `unique (usuario_id, rede,
  contrato)` (contrato nulo para o nativo, `NULLS NOT DISTINCT`). Desvia de
  `dados.md`: o CLAUDE.md diz que toda tabela tem `usuario_id`, e
  `confiavel` é decisão de cada usuário; um catálogo global deixaria a
  promoção de um usuário mudar o patrimônio de outro.
- Redes da F13: `bitcoin`, `ethereum`, `base`, `arbitrum`, `polygon`,
  `solana`. Contrato: nulo para o nativo; nas redes EVM, `0x` mais 40
  hexadecimais, gravado em minúsculas; em Solana, base58 de 32 a 44
  caracteres; em Bitcoin, sempre nulo. Símbolo de 1 a 20, nome até 100,
  decimais de 0 a 18.
- Ativo digitado pelo usuário nasce `confiavel = true`: foi ele quem disse
  que existe. O `false` fica para o que vier da sincronização (F24).

### Conta e posição

- O tipo de conta `cripto` passa a valer. Conta cripto não tem saldo em
  reais: `saldo_inicial` 0, não recebe lançamento, não entra em transferência,
  cartão, benefício, recorrência, compra parcelada nem no saldo projetado.
- **Posição não é coluna.** A quantidade de um ativo numa conta é a soma das
  movimentações, como o saldo da conta é a soma dos lançamentos (CLAUDE.md,
  "Saldo não é coluna"). `posicao_cripto` com quantidade guardada fica para a
  sincronização da F24, com ADR próprio.

### Movimentação

- `movimento_cripto`: `usuario_id`, `conta_id` (conta cripto do mesmo
  usuário, FK composta com o tipo), `ativo_id` (do mesmo usuário), `tipo`
  (`compra` ou `venda`), `quantidade numeric(38,18) > 0` com no máximo as
  casas decimais do ativo, `data`, `custo numeric(18,2) > 0` (o que pagou na
  compra ou recebeu na venda, em reais) e `criado_em`.
- **Venda não deixa posição negativa.** A escrita trava a linha da conta com
  `FOR UPDATE` e confere a soma antes de gravar; apagar uma compra que deixaria
  alguma data com posição negativa também é recusado.
- O lado em reais da compra (o dinheiro que saiu da corrente) não é ligado na
  F13: o usuário lança a saída na conta dele, se quiser. Ligar as duas pontas é
  o assunto da F15 (aporte e valorização).

### Cotação manual

- `cotacao` com `usuario_id`, `ativo_id`, `momento`, `preco numeric(20,8) >
  0` em reais por unidade e `fonte` (`manual` na F13). Chave `(ativo_id,
  momento)`.
- `POST /ativos-cripto/{id}/cotacoes` grava uma cotação manual. Sem provedor,
  é o único jeito de o patrimônio ter valor; a F14 acrescenta a fonte
  automática na mesma tabela.

### JSON

- Quantidade e preço trafegam como **string decimal** (ADR-005: `Number` não
  representa 18 casas). Custo e valor em reais como inteiro em centavos.
- Valor de uma posição = quantidade × preço, arredondado **uma vez** na saída
  para centavos com `RoundingMode.HALF_EVEN`: não há direção certa para o erro
  de meio centavo numa avaliação, e o arredondamento bancário não acumula
  viés numa soma de várias posições.

### Patrimônio com data

- `GET /patrimonio?data=AAAA-MM-DD` (padrão hoje, em `America/Sao_Paulo`):
  reais das contas ativas não cripto (`saldo_inicial` mais realizados com
  `data_caixa` até a data, inclusive cartão e benefício), posições de cripto
  até a data com a última cotação até a data, o total, e as posições sem
  cotação listadas à parte com o valor nulo. Nunca falha por falta de cotação.
- `GET /posicoes-cripto?data=` lista as posições com quantidade, custo
  acumulado das compras menos o das vendas, última cotação (preço e momento)
  e valor.

### Rotas

`POST` e `GET /ativos-cripto`; `POST /ativos-cripto/{id}/cotacoes`; `POST`,
`GET` e `DELETE /movimentos-cripto`; `GET /posicoes-cripto`; `GET
/patrimonio`. Tudo escopado por `usuario_id`, com 404 igual para o que é de
outro usuário.

## Justificativa

Posição derivada mantém uma fonte de verdade só e reaproveita a trava por
conta que o projeto já usa para dinheiro.

Cotação manual faz a F13 entregar o que promete (patrimônio com data) sem
inventar provedor, que é decisão do dono.

## Alternativas descartadas

- **Catálogo de ativos global**: quebra a fronteira por usuário e mistura a
  decisão de confiança.
- **`posicao_cripto` guardando quantidade já na F13**: dois lugares para a
  mesma verdade, e a soma diverge na primeira corrida.
- **Patrimônio só em quantidade, sem valor até a F14**: não responde "quanto
  eu tenho".
- **Ligar a compra a uma saída da corrente**: precisa de um tipo de
  lançamento novo e é exatamente a F15.

## Consequência

`dados.md` registra o desvio do ativo por usuário, a posição derivada e a
cotação por usuário. A F14 grava na mesma `cotacao` com outra `fonte`. A F24
traz a posição sincronizada.
