# ADR-037: Renda fixa (LCI, LCA, CDB) na F13.2

**Status:** aceito
**Data:** 2026-10-22

## Contexto

O dono quer acompanhar LCI, LCA e CDB: quanto aplicou, quanto vale hoje e,
no CDB, quanto sobra depois do imposto. Ele escolheu o cálculo **híbrido**: o
app calcula sozinho com os índices do Banco Central e aceita o valor que o
banco mostra para corrigir. Escolheu também mostrar no CDB o bruto e o líquido
estimado. `visao.md` deixa imposto fora do escopo: aqui o IR é só exibição de
estimativa, não declaração.

O Banco Central publica as séries de graça e sem chave (SGS):
`https://api.bcb.gov.br/dados/serie/bcdata.sgs.{n}/dados?formato=json&dataInicial=dd/MM/aaaa&dataFinal=dd/MM/aaaa`.
CDI é a série 12 (% ao dia, um valor por dia útil) e IPCA é a 433 (% ao mês,
um valor por mês). A F13.3 vai usar a mesma fonte para o dólar (série 1).

## Decisão

### Conta e título

- O tipo de conta `investimento` passa a valer, com as regras da conta cripto
  (ADR-035): saldo inicial 0, não recebe lançamento, não entra em
  transferência, cartão, recorrência nem saldo projetado. É a corretora ou o
  banco onde os títulos estão.
- `titulo_renda_fixa`: `usuario_id`, `conta_id` (conta `investimento` do mesmo
  usuário, FK composta com o tipo), `tipo` (`lci`, `lca`, `cdb`), `nome` (1 a
  100), `valor_aplicado numeric(18,2) > 0`, `data_aplicacao`, `vencimento`
  (opcional, depois da aplicação), `indexador` (`prefixado`, `cdi`, `ipca`),
  `taxa numeric(9,4) > 0` e, quando resgatado, `data_resgate` e
  `valor_resgate`.
- `taxa` quer dizer: no prefixado, % ao ano (12,5 é 12,5% a.a.); no CDI, % do
  CDI (95 é 95% do CDI); no IPCA, % ao ano acima do IPCA (6 é IPCA + 6%).
- `marcacao_renda_fixa`: `usuario_id`, `titulo_id`, `data`, `valor`. É o valor
  que o banco mostrou naquela data. Chave `(titulo_id, data)`.

### Índices

- `indice` com `serie` (`cdi`, `ipca`), `data` e `valor numeric(12,8)` (o
  percentual publicado). **Sem `usuario_id`.** Desvia da regra do CLAUDE.md
  ("toda tabela tem `usuario_id`") porque é dado público de mercado, igual
  para todos e sem dono; nenhuma rota escreve nela a partir do cliente.
- Um job baixa o que falta, uma vez ao subir e todo dia às 8h em
  `America/Sao_Paulo`, desde a primeira aplicação cadastrada (ou um ano atrás,
  o que for antes), em pedaços de até 5 anos (o SGS limita a janela das séries
  diárias). Cliente HTTP com timeout de 5 s. **Banco Central fora não trava
  nada**: o job loga e tenta no dia seguinte; o cálculo usa o que já tem e a
  resposta diz até que data há índice.
- Também baixa ao cadastrar título com aplicação anterior ao primeiro índice
  guardado, fora da transação do cadastro.

### Cálculo (valor bruto numa data D)

O ponto de partida é a última marcação até D (data M, valor V); sem marcação,
a aplicação (data e valor aplicado). Sobre ele aplica-se o fator de M até D:

- **Dias úteis** são os dias com CDI publicado. Dia útil depois do último CDI
  guardado (o de hoje só sai amanhã) conta de segunda a sexta e usa o último
  CDI conhecido.
- **Prefixado**: fator = (1 + taxa)^(du/252), du = dias úteis em [M, D). A raiz
  252 é calculada em `BigDecimal` por Newton, com 34 dígitos; nada passa por
  `double`.
- **% do CDI**: fator = Π (1 + CDI_d × taxa/100) para cada dia útil d em
  [M, D).
- **IPCA + taxa**: fator = Π (1 + IPCA_m) dos meses, com o mês parcial na
  proporção dos dias corridos e, enquanto o IPCA do mês não sai, o último
  publicado; vezes (1 + taxa)^(du/252). É estimativa: o banco usa a projeção
  do IPCA e o aniversário do título, e a marcação corrige.
- Valor = V × fator, arredondado **uma vez** para centavos com
  `RoundingMode.HALF_EVEN` (mesma justificativa do ADR-035).
- Título resgatado vale 0 depois da data de resgate e mostra o valor resgatado.

### Imposto (só CDB, estimativa)

- Rendimento = bruto menos aplicado. IOF nos primeiros 30 dias corridos pela
  tabela regressiva (96% no dia 1 até 0% no dia 30), sobre o rendimento. IR
  sobre o rendimento menos o IOF: 22,5% até 180 dias, 20% até 360, 17,5% até
  720 e 15% depois. Rendimento negativo não tem imposto.
- LCI e LCA são isentas: líquido igual ao bruto.
- Cada imposto é arredondado para centavos com `HALF_EVEN`, e o líquido é o
  bruto menos os dois.

### Rotas

- `POST`, `GET` e `PUT /titulos-renda-fixa`, `DELETE /titulos-renda-fixa/{id}`.
  O `GET ?data=` traz o valor bruto, o IR e o IOF estimados, o líquido e o
  rendimento na data.
- `POST /titulos-renda-fixa/{id}/marcacoes` grava o valor que o banco mostrou.
- `POST /titulos-renda-fixa/{id}/resgate` com data e valor recebido.
- `GET /indices` diz até que data há CDI e IPCA guardados.
- `GET /patrimonio` ganha `rendaFixaCentavos`, a soma do **líquido** de cada
  título na data: é o que se receberia resgatando, e para LCI e LCA é o bruto.

## Justificativa

Usar a própria série do CDI como calendário de dias úteis evita manter a lista
de feriados da Anbima. Marcação como ponto de partida faz a correção do usuário
valer dali em diante sem apagar o cálculo anterior.

## Alternativas descartadas

- **Só manual**: o valor fica velho se o usuário esquecer de atualizar.
- **Só automático**: diverge alguns centavos do banco sem jeito de corrigir.
- **`indice` por usuário**: repetiria a mesma série para todo usuário e o job
  baixaria tudo N vezes.
- **`double` com `Math.pow` para a raiz**: o CLAUDE.md proíbe `double` em
  dinheiro, inclusive em passo intermediário.

## Consequência

`dados.md` registra as três tabelas e o desvio do `indice`. A F13.3 grava o
dólar na mesma `indice`. O web junta renda fixa e cripto numa aba
Investimentos, com o patrimônio no topo.
