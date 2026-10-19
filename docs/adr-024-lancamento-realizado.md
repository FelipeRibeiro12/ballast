# ADR-024: Lançamento realizado e categorias na F3

**Status:** aceito
**Data:** 2026-10-19

## Contexto

A F3 traz receita e despesa realizadas, com categoria, e faz o saldo da conta
reagir. O modelo em `docs/contracts/dados.md` descreve `lancamento` e
`categoria` inteiros, com recorrência, parcela, importação e transferência,
que só chegam depois. Faltava fixar o recorte da F3, como o banco garante o
dono e como as categorias nascem.

## Decisão

- **Tabelas.** `categoria` e `lancamento`, em português como `conta`. Pacotes
  Java `categories` e `entries`.
- **Recorte de `lancamento`.** Só as colunas que a F3 usa: `id`,
  `usuario_id`, `tipo`, `titulo`, `descricao`, `categoria_id`, `conta_id`,
  `valor`, `data_competencia`, `data_caixa`, `status`, `criado_em`. Recorrência,
  parcela, importação e transferência acrescentam as suas colunas por
  migration.
- **Tipo e status restritos.** `tipo` aceita `receita` e `despesa`; a F4
  alarga para `transferencia`. `status` aceita só `realizado`; a recorrência
  alarga para `previsto` e `cancelado`. Mesmo critério do ADR-023.
- **As duas datas.** As duas colunas existem desde já. A API da F3 recebe uma
  `data` só e grava a mesma nas duas; a resposta devolve `dataCompetencia` e
  `dataCaixa`. Ano entre 1900 e 2199.
- **Valor.** `valor numeric(18,2)` sempre positivo, o sinal vem do `tipo`. No
  JSON, `valorCentavos` inteiro de 1 a 9.007.199.254.740.991 (ADR-023).
- **O banco garante o dono.** `conta` e `categoria` ganham `unique (id,
  usuario_id)`, e `lancamento` referencia as duas por FK composta
  `(conta_id, usuario_id)` e `(categoria_id, usuario_id)`. Lançamento com
  conta ou categoria de outro usuário não grava nem se a aplicação errar.
  A categoria também carrega o tipo na FK: `(categoria_id, usuario_id, tipo)`
  contra `unique (id, usuario_id, tipo)` em `categoria`, para despesa nunca
  apontar para categoria de receita.
- **Validação na aplicação também.** Conta ou categoria inexistente, de outro
  usuário ou de tipo errado dá 400 com a mesma mensagem para os três casos de
  cada campo. Conta arquivada dá 400 `conta arquivada`.
- **Categorias semeadas por usuário.** A lista de `dados.md` nasce no cadastro
  do usuário, na mesma transação, por evento síncrono que o módulo de
  categorias escuta. Usuários que já existem recebem as sementes na própria
  migration. "Outros" é despesa; a receita genérica chama "Outras receitas",
  porque o nome é único por usuário. Na F3 não há CRUD de categoria, só
  `GET /categorias`.
- **Rotas.**
  - `POST /lancamentos` cria e devolve 201.
  - `GET /lancamentos?de=AAAA-MM-DD&ate=AAAA-MM-DD`, com `contaId` opcional,
    filtra por `data_caixa`, do mais novo para o mais antigo. `de` e `ate`
    obrigatórios, intervalo de no máximo 366 dias: o resultado tem teto sem
    paginação.
  - `GET /lancamentos/{id}`, `PUT /lancamentos/{id}` (mesmo corpo do POST) e
    `DELETE /lancamentos/{id}` (204). De outro usuário ou inexistente, 404.
  - `DELETE` apaga a linha. `cancelado` fica para a recorrência, onde
    existe o que cancelar.
- **Saldo.** `saldo_inicial` mais receitas realizadas menos despesas
  realizadas, numa consulta agregada só para a lista de contas. Saldo acima
  de ±9.007.199.254.740.991 centavos está fora do contrato: com o teto por
  lançamento, precisaria de milhares de lançamentos no limite.

## Justificativa

A FK composta move a regra "conta validada contra o dono" para o banco, onde
duas requisições simultâneas não furam. Custa um índice único extra por
tabela referenciada, que já é a chave primária com mais uma coluna.

Semear por usuário mantém a regra de que toda tabela tem `usuario_id`.
Categoria global sem dono seria a primeira exceção da fronteira de
autorização.

Período obrigatório na listagem dá teto ao resultado sem inventar paginação
antes de haver volume.

## Alternativas descartadas

- **Categorias globais com `usuario_id` nulo**: quebra a fronteira de
  autorização e complica o `unique`.
- **Status `cancelado` no lugar do `DELETE`**: guarda lixo sem dar nada ao
  usuário enquanto não houver recorrência.
- **`data_competencia` e `data_caixa` separadas na API já agora**: sem
  cartão não há caso em que difiram, e o formulário ficaria com um campo que
  confunde.

## Consequência

A F4 (transferência) precisa alargar o `CHECK` de tipo e permitir
`categoria_id` nulo só para `transferencia`. A recorrência alarga o `status`.
