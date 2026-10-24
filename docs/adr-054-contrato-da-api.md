# ADR-054: Ajustes de contrato da API

**Status:** aceito
**Data:** 2026-10-24

## Contexto

Oito pontos de contrato ficaram abertos depois da F12 e da revisão do QA. Seis são
conserto de coerência. As decisões 7 e 8 são **do dono**, tomadas em 2026-10-24:
a 7 emenda o ADR-026 e a 8 emenda o ADR-032.

## Decisão

### 1. Tipo `transferencia` em `/lancamentos`

`POST` e `PUT /lancamentos` com `tipo` igual a `transferencia` dão 400 `transferência
só pela rota /transferencias`. Antes a mensagem era `requisição inválida`, que não
dizia o que fazer.

### 2. `descricao` nula limpa no PATCH da compra parcelada

Em `PATCH /compras-parceladas/{id}`, `descricao` ausente mantém, e nula, vazia ou em
branco limpa. Título e categoria ausentes ou nulos continuam não mudando: não têm
estado "limpo". Nenhum outro PATCH do app tem campo anulável.

### 3. Categorias permitidas em conta arquivada

`PUT /contas/{id}/categorias-permitidas` dá 400 `conta arquivada`, como as outras
escritas em conta. Fica depois da recusa por conta que não é de benefício.

### 4. Editar recorrência inativa de conta arquivada

`PUT /recorrencias/{id}` em recorrência inativa não olha conta arquivada: editar o
template não gera nada. Ativar e gerar ocorrência continuam recusando. Recorrência
ativa mantém a recusa.

### 5. Mensagens de 400 no OpenAPI

As descrições de 400 de `/lancamentos`, `/recorrencias` e `/transferencias` listam
`categoria não permitida nesta conta`, `conta de benefício não participa de
transferência` e as demais mensagens novas. `api/openapi.yaml` e o cliente TS são
regenerados no mesmo commit (ADR-019).

### 6. Erro de posição cripto

`GET /posicoes-cripto` fora de ±9007199254740991 dá 400 `posição fora do limite`.
`/patrimonio` mantém `patrimônio fora do limite`.

### 7. Ocorrência cancelada e apagada não volta (decisão do dono; emenda o ADR-026)

O ADR-026 dizia que apagar ocorrência cancelada liberava a chave e a regra gerava a
data de novo como prevista. Agora ela some de vez.

- Migration `V22` cria `ocorrencia_apagada (template_id, usuario_id, ocorrencia_data,
  parcela_ordem)`, com `parcela_ordem` 0 para template sem divisão. Foi tabela à parte
  em vez de linha oculta em `lancamento`: ocultar exigiria filtro em toda consulta de
  lançamento, saldo e relatório, e um esquecimento faria a ocorrência aparecer.
- `DELETE /lancamentos/{id}` em ocorrência `cancelado` grava a marca na mesma
  transação do delete. A materialização pula as chaves marcadas.
- A FK para o template é `ON DELETE CASCADE`: apagar a recorrência ou a conta leva as
  marcas sem mexer nos dois caminhos que apagam template.
- Apagar ocorrência `realizado` não muda: libera a chave e a regra gera de novo se a
  data é de hoje em diante e a recorrência segue ativa.
- Não há como desfazer o apagar. Quem quer manter a ocorrência fora do saldo sem
  perdê-la continua podendo cancelar e não apagar.

### 8. PUT de parcela não muda título nem categoria (decisão do dono; emenda o ADR-032)

O ADR-032 deixava `PUT /lancamentos/{id}` mudar título e categoria de uma parcela. Isso
fazia a compra divergir: a tela mostra o título da primeira parcela e o gráfico por
competência contava a parcela na categoria dela. Agora esse `PUT` dá 400 `edite a
compra inteira` quando título ou categoria diferem do gravado, como já dava para conta
e data. Descrição e valor da parcela continuam editáveis. Título e categoria mudam pelo
`PATCH /compras-parceladas/{id}`.

## Consequências

- O cliente que mandava título ou categoria novos no `PUT` de uma parcela passa a
  receber 400. O corpo do `PUT` tem de repetir título e categoria da parcela.
- `docs/contracts/api.md` e `docs/contracts/dados.md` refletem as decisões 2, 3, 4, 6,
  7 e 8.
- A V22 só cria tabela; desfazer à mão é `DROP TABLE ocorrencia_apagada` e as
  ocorrências apagadas voltam como previstas na rodada seguinte do job.
