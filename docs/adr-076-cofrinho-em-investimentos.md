# ADR-076: O usuário escolhe quais cofrinhos contam em Investimentos

**Status:** aceito
**Data:** 2026-10-30

## Contexto

A tela Investimentos soma renda fixa e cripto (ADR-047 e o ajuste da PR 54). O dono
guarda dinheiro em cofrinhos e quer que alguns contem como investimento, por
escolha dele, conta por conta. Nada de marcar todos nem de decidir pelo tipo.

## Decisão

- `conta.em_investimentos boolean not null default false` (V39). O CHECK
  `conta_em_investimentos_so_no_cofrinho` aceita `true` só no tipo `cofrinho`.
  O padrão é desmarcado: nenhum cofrinho entra até o usuário escolher.
- `PUT /contas/{id}/investimentos` com `{emInvestimentos: boolean}`. O campo é
  obrigatório e campo não declarado dá 400 (whitelist global). Escopado por
  `usuario_id` com a mesma trava da conta do `PUT /contas/{id}/cofrinho`: conta de
  outro usuário, inexistente ou id malformado dá 404; conta que não é cofrinho dá
  400; marcar conta arquivada dá 400. Desmarcar arquivada passa, para o usuário
  limpar a escolha. Idempotente por natureza (grava o mesmo valor), sob o limite
  geral por IP (ADR-049). Responde a conta inteira.
- `GET /contas` e `GET /contas/{id}` devolvem `emInvestimentos` (falso fora do
  cofrinho).
- `GET /patrimonio` ganha `cofrinhosCentavos` (saldo na data dos cofrinhos ativos
  marcados, mesma conta de `contasCentavos`) e `cofrinhos: [{contaId, nome,
  saldoCentavos}]`. **`contasCentavos` e `totalCentavos` não mudam**: o cofrinho
  já está em `contasCentavos`, então o campo novo é um recorte e não entra de
  novo na soma. Cofrinho arquivado não aparece, como já não entra no patrimônio.
  Data passada usa o saldo daquela data.
- A tela soma `rendaFixaCentavos + cofrinhosCentavos` no valor de Renda fixa e
  esse valor mais a cripto no Total investido. Posso gastar e saldos não mudam: o
  cofrinho continua disponível lá.
- A engrenagem fica no cabeçalho da seção Renda fixa e abre um painel com um
  checkbox por cofrinho ativo. Cada clique salva aquela conta. Não há "marcar
  todos". Os marcados aparecem em lista dentro da seção, com nome e saldo na data.
- O espaçamento: o cartão destacado não tinha margem e colava nas abas. A margem
  fica no contêiner `.investimentos-resumo`, que vale para qualquer largura.

## Consequência

- Mais uma coluna em `conta`, sem mudar saldo nem lançamento.
- A lista de contas do App busca de novo depois de cada escolha (via a versão de
  saldo) para a engrenagem ler o flag atual.
- O patrimônio mensal (`/patrimonio/mensal`) não ganha o campo novo; ele usa só o
  total, que não muda.
