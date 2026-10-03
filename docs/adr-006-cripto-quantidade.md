# ADR-006: Cripto guarda quantidade, não valor

**Status:** aceito
**Data:** 2026-10-03

## Contexto

Saldo em reais é um fato: você tem R$ 1.000. Posição em cripto não é: você tem
0,015 BTC, e quanto isso vale depende do momento em que a pergunta é feita.

Decidido na conversa de concepção que a cotação será automática, com histórico
para gráfico.

## Decisão

- A posição guarda **quantidade** do ativo. Valor em reais nunca é armazenado
  como verdade.
- Cotação é buscada por um worker interno (goroutine com ticker) e gravada em
  série histórica.
- Valor em reais é sempre **derivado**: `quantidade × cotação do momento
  pedido`.
- Movimentação de cripto (compra, venda, transferência) registra quantidade e
  a cotação vigente no momento, para permitir calcular aporte separado de
  valorização.

## Justificativa

Guardar valor convertido congelaria uma mentira: o número envelhece sozinho e
não há como recalcular o passado.

Guardar a cotação no momento da movimentação é o que permite responder "quanto
desse crescimento foi porque eu aportei e quanto foi porque o preço subiu" —
sem isso, cripto valorizando parece economia, o que distorce toda análise de
gasto.

## Alternativas descartadas

- **Guardar valor em reais na movimentação e ignorar cotação atual** — simples,
  mas o patrimônio exibido fica errado no dia seguinte.
- **Buscar cotação sob demanda, sem histórico** — impede gráfico de patrimônio
  ao longo do tempo e deixa o app refém da disponibilidade da API.

## Consequência

- Patrimônio total passa a ser função do tempo. Todo endpoint e gráfico que
  soma fiat com cripto recebe uma data de referência.
- A API de cotação vai falhar. O app precisa: cache com a última cotação
  conhecida, exibição da data dessa cotação, e nunca travar uma operação de
  lançamento por causa de cotação indisponível.
- Escolha do provedor fica para a fatia 14. Candidato inicial: CoinGecko, por
  ter plano gratuito e histórico.

## Em aberto

Se a cripto for usada como dinheiro gastável (stablecoin pagando contas) e não
só como investimento, entra a decisão de incluí-la no saldo disponível e no
cálculo de "posso gastar quanto". Hoje está modelada apenas como investimento.
