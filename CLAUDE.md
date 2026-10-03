# Ballast

App de controle financeiro pessoal: fiat e cripto no mesmo lugar, com
recorrência, cartão de crédito parcelado, metas e projeção de saldo.

Contexto do produto em `docs/visao.md`. Decisões em `docs/adr-*.md`. Modelo de
dados em `docs/contracts/dados.md` — **leia antes de mexer em qualquer tabela.**

<!-- Mantenha abaixo de 200 linhas: arquivo grande consome mais contexto e
     reduz a aderência. Convenção de Go está em .claude/rules/go.md e de banco
     em .claude/rules/sql.md, que carregam só quando o arquivo é lido. -->

## Stack

- Go no backend, monolito modular (ADR-001, ADR-002)
- Postgres (ADR-003)
- React + Vite como PWA, mobile-first (ADR-004)
- Monorepo: `api/` e `web/`
- Module path do Go: `github.com/FelipeRibeiro12/ballast`
- OpenAPI como fonte da verdade do contrato, em `api/openapi.yaml`; cliente TS
  gerado no build. `docs/contracts/api.md` só aponta para ela

Não troque nada disso sem pedir antes. Está justificado nos ADRs.

## Estrutura

```
api/
├── cmd/api/              # binário: só monta e sobe
├── internal/
│   ├── contas/
│   ├── lancamentos/
│   ├── recorrencia/
│   ├── cartao/
│   ├── metas/
│   ├── orcamento/
│   ├── cotacoes/         # worker interno, goroutine com ticker
│   └── http/             # handlers, rotas, middleware
└── migrations/
web/
└── src/
```

**Pacote não importa o interno de outro.** A comunicação é por interface
definida no pacote que consome. Essa disciplina é o que permitiria separar algo
no futuro — e é só disciplina, o compilador não força.

## Dinheiro — regras absolutas

- **Fiat em inteiro de centavos** (`int64`). Nunca float, nunca string.
- **Cripto em decimal** (`numeric(38,18)` no banco, `shopspring/decimal` no
  Go). `int64` estoura em ~9 ETH — não serve.
- **No JSON, fiat trafega como inteiro em centavos**; cripto como string.
  Evita o JavaScript fazer aritmética em float.
- Arredondamento acontece **uma vez, na exibição**. Nunca no meio do cálculo.
- Divisão com resto (parcela, percentual de salário) tem regra explícita: resto
  vai para a última parcela, e a soma **tem que** fechar com o total.

Detalhes em ADR-005.

## As duas datas

Todo lançamento tem `data_competencia` (quando o fato aconteceu) e `data_caixa`
(quando o dinheiro se move). Em débito são iguais; em cartão, não.

- Gráfico por categoria e orçamento usam **competência**
- Saldo e saldo projetado usam **caixa**

Confundir as duas é o bug mais provável deste projeto.

## Autorização

**Toda tabela tem `usuario_id` e toda query é escopada por ele.** É a única
fronteira de autorização do app.

- Busca por id filtra por `usuario_id` **e** `id`, nunca só por `id`
- Nunca confie em identificador vindo do cliente para decidir dono
- Conta informada num lançamento é validada contra o dono antes de gravar
- Toda rota tem teste: logar como A e pedir recurso de B deve dar 404

Ameaças em `docs/ameacas.md`.

## Cripto — limites absolutos

O app **observa** endereço. Não é carteira (ADR-009).

- Nunca pedir seed ou chave privada. Nenhum motivo justifica, e a interface
  deve dizer que o app não pede.
- Nunca `eth_sendTransaction`, nunca `approve`, nunca assinatura de transação.
  Só endereço e, opcional, assinatura de mensagem provando posse.
- Programe contra o padrão, não contra a carteira: EIP-6963 no EVM, Wallet
  Standard no Solana. Rabby e Phantom aparecem na lista como qualquer outra.
- Digitar o endereço é caminho de primeira classe e tem que continuar
  funcionando sem nenhuma extensão instalada.
- Identidade do ativo é `rede` + `contrato`, nunca o símbolo.
- Endereço não vai para log, nem para URL. A associação pessoa ↔ endereço é o
  dado sensível, não o endereço.

## Importação é entrada não confiável

Mesmo sendo o próprio usuário enviando o arquivo: limite de tamanho e de
linhas, XXE desabilitado no parser de OFX em XML, fórmula escapada em CSV, nome
de arquivo nunca compondo caminho de disco.

Toda importação passa por revisão. Nada entra no extrato sem confirmação
(ADR-008).

## Saldo não é coluna

Saldo é `saldo_inicial` + soma dos lançamentos realizados. Guardar saldo
materializado é a origem clássica de divergência e de corrida entre duas
requisições. Se a performance exigir, vira view materializada com ADR.

## Como trabalhar

**Primeiro fazer funcionar, depois melhorar.** Não é negociável.

1. **Teste que falha**, pelo motivo certo
2. **Implementação mais burra que passa** — pode ser feia, pode ser hardcoded.
   Sem abstração antecipada, sem interface para uma implementação só
3. **Suíte verde** → commit
4. **Refatorar**, em commit separado, mostrando antes o que muda e por quê

Uma fatia do `docs/backlog.md` por vez. Se eu pedir a F5, não adiante nada da
F6, mesmo que pareça óbvio.

## Testes que não podem faltar

Estes são os que pegam os bugs reais deste domínio:

- Recorrência: dia 31 em fevereiro, ano bissexto, intervalo maior que 1,
  materialização rodada duas vezes (idempotência)
- Divisão: três partes de 33,33% de R$ 1.000 somando exatamente R$ 1.000
- Cartão: fechamento dia 31 em fevereiro, vencimento menor que fechamento,
  compra no próprio dia do fechamento
- Autorização: usuário A pedindo recurso de B, em toda rota
- Cotação: API fora do ar não trava lançamento

## Git

- Conventional commits: `feat:`, `fix:`, `test:`, `refactor:`, `docs:`,
  `chore:`. Mensagem em português, imperativo.
- Um commit por passo lógico. Implementação e refatoração separados.
- Não commite com teste quebrado.
- Fatia fecha com `/pr`.

## Comandos

```bash
make dev        # go run ./cmd/api
make test       # go test -race ./...
make lint       # go vet + golangci-lint
make migrate    # aplica migrations
cd web && pnpm dev
cd web && pnpm test
```

## Regras

- Não instale dependência nova sem me perguntar antes.
- Migration aplicada não se edita: escreva outra.
- Mudança no modelo atualiza `docs/contracts/dados.md` no mesmo PR.
- Não gere chave privada, nem coloque chave, seed ou `.env` no repositório.
- Se algo aqui estiver desatualizado ou conflitar com o que eu pedi na
  conversa, me avise em vez de escolher sozinho.
- Se eu pedir algo que vai dar problema mais na frente, fale agora.

## A completar

Não invente conteúdo para estes. Se precisar, pergunte.

- Usuário do GitHub, para o module path (`github.com/<usuario>/ballast`)
- Provedor de cotação de cripto — candidato: CoinGecko (fatia 14)
- Hospedagem de API, banco e PWA
- RTO e RPO confirmados (primeira versão em `docs/ameacas.md`)
