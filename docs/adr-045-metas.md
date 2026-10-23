# ADR-045: Metas na F17

**Status:** aceito
**Data:** 2026-10-23

## Contexto

A F17 pede criar meta, aporte recorrente, progresso e "no ritmo atual chego em
tal data". O rascunho de `dados.md` tinha `meta` com `conta_id` e `regra_id`
(recorrência) e `aporte_meta` com `lancamento_id`.

## Decisão

### Aporte à mão

- `aporte_meta` guarda valor e data, à mão. Positivo guarda, negativo retira.
  Zero não vale.
- A retirada não pode deixar o acumulado negativo. Apagar um aporte do qual uma
  retirada depende é recusado pela mesma regra.
- As duas escritas travam a meta com `FOR UPDATE` para conferir a soma, como as
  escritas de dinheiro (ADR-026).
- A data do aporte vai até hoje em São Paulo: aporte é o que já aconteceu.

### Aporte mensal é plano

`aporte_mensal` é o que a pessoa pretende guardar por mês. Nada se lança
sozinho (ADR-028), e por isso não há `regra_id`. O formulário de aporte já
vem preenchido com esse valor.

### Projeção

Calculada na leitura (`GoalProjection`). Os meses contam o atual: com alvo em
dezembro, de outubro são três.

- **Falta:** alvo menos acumulado, nunca abaixo de zero. Chegar no alvo exato
  conta como atingida.
- **Necessário por mês:** o que falta dividido pelos meses até a data alvo,
  arredondado **para cima**. Arredondar para baixo deixaria a soma um centavo
  abaixo do alvo. É nulo sem data, com o prazo vencido ou com a meta atingida.
- **Chegada pelo plano:** em que mês o aporte mensal cobre o que falta.
- **Chegada pelo ritmo:** o mesmo, com a média dos três meses fechados antes do
  atual. O mês corrente fica fora porque ainda não acabou. Sem ritmo positivo,
  não há data.
- Chegada depois de 2199, o último ano que as datas da API aceitam, fica sem
  data em vez de estourar.

### Banco

- `V19` cria `meta` e `aporte_meta`. O aporte aponta para a meta do mesmo
  usuário por FK composta, e apagar a meta leva os aportes (CASCADE).
- Faixas no CHECK: título de 1 a 100 sem espaço nas pontas, valores até o
  inteiro seguro do JSON, datas de 1900 a 2199.

### Tela

- Seção `#/metas`, fora da barra de navegação pelo mesmo motivo do Orçamento
  (ADR-043).
- Entradas: um cartão no Início, com até três metas e as abertas primeiro, e
  um par de links "Orçamento | Metas" no topo das duas telas.

## Justificativa

Aporte à mão é exato e não depende de adivinhar qual transferência foi para a
meta. O plano mensal responde "quando chego" sem criar lançamento que ninguém
confirmou.

## Alternativas descartadas

- **Progresso pelo saldo de uma conta (`conta_id`)**: a mesma conta costuma
  guardar dinheiro de várias metas e de fora delas.
- **Aporte ligado a lançamento ou transferência (`lancamento_id`)**: precisa
  decidir o que acontece quando o lançamento é editado ou apagado. Ficou no
  backlog.
- **Recorrência gerando o aporte**: contraria o ADR-028, e a recorrência hoje
  só faz receita e despesa.
- **Ritmo pela média desde a criação**: uma meta antiga com aportes no começo
  daria uma data otimista para quem parou de guardar.

## Consequência

A pessoa registra o aporte e também a transferência na conta, se quiser os dois
lados. Já são duas seções fora da barra de navegação: a próxima pede outro
desenho de navegação, que está no backlog.
