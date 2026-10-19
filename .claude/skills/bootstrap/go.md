# Bootstrap — Go

Confira `go version` antes. Go não tem gerador oficial de projeto: a estrutura
é convenção, então aqui ela é montada à mão.

## Módulo e estrutura

```bash
mkdir api && cd api
go mod init github.com/<usuario>/<projeto>
mkdir -p cmd/api internal/{domain,http,store} migrations
```

```
cmd/api/main.go        # binário: só monta e sobe
internal/domain/       # regra de negócio, sem HTTP, sem SQL
internal/http/         # handlers, rotas, middleware
internal/store/        # acesso a banco
migrations/            # faixa do agente dba
```

`internal/` não é estilo: o compilador impede que outro módulo importe de lá.
É a fronteira mais barata que existe em Go.

Não crie `pkg/` sem alguém de fora para consumir. Não crie camada vazia "por
enquanto".

## Dependências

A biblioteca padrão resolve mais do que parece. O roteador do `net/http` da
stdlib já trata método e parâmetro de caminho — comece com ele.

Quando precisar de mais (middleware encadeado, grupos de rota), `chi` é a
escolha que menos se afasta da stdlib:

```bash
go get github.com/go-chi/chi/v5
```

Banco: `database/sql` com o driver, e `sqlc` ou `pgx` quando quiser tipo
gerado. ORM completo em Go costuma custar mais do que entrega — registre no ADR
se for usar.

Migrations: `goose` ou `golang-migrate`. Escolha uma.

## Comandos

```bash
go build ./...
go test ./...
go test -race ./...        # sempre no CI: corrida não aparece sem isso
go test -cover ./...
go vet ./...
gofmt -l .                 # lista arquivo desformatado; vazio = ok
```

Lint mais completo:

```bash
brew install golangci-lint     # ou o gerenciador do seu sistema
golangci-lint run
```

**Confira a compatibilidade com a sua versão de Go.** O golangci-lint anda
atrás dos lançamentos da linguagem: cada versão nova de Go exige uma versão
mínima do linter, e com uma mais antiga ele falha em vez de avisar. Se
`golangci-lint run` quebrar logo depois de você atualizar o Go, é quase sempre
isso.

Evite `go install ...@latest` para instalar o linter: a versão passa a variar
entre a sua máquina e o CI, e lint que reprova só num dos dois é pior que não
ter lint. Instale pelo gerenciador de pacotes e **fixe a mesma versão no CI**.

## Makefile

Para expor os mesmos verbos de qualquer projeto meu:

```makefile
.PHONY: dev build test lint

dev:   ; go run ./cmd/api
build: ; go build -o bin/api ./cmd/api
test:  ; go test -race ./...
lint:  ; go vet ./... && golangci-lint run
```

## Idioma da linguagem

- **Erro é valor.** Trate na hora ou embrulhe com contexto:
  `fmt.Errorf("busca remessa %s: %w", id, err)`. O `%w` preserva a cadeia para
  `errors.Is` e `errors.As`.
- **Nunca ignore erro** com `_`. Se realmente não importa, comente por quê.
- `panic` só em falha de inicialização. Nunca em handler.
- **Aceite interface, devolva struct.** Defina a interface no pacote que
  consome, não no que implementa.
- Interface pequena: uma ou duas funções. `io.Reader` é o padrão a seguir.
- `context.Context` como primeiro parâmetro em tudo que faz I/O, e respeite o
  cancelamento.
- Sem estado global mutável.

## Teste

Teste fica ao lado do código, `arquivo_test.go`, mesmo pacote. Sem framework:
`testing` da stdlib basta.

Tabela de casos é o padrão da comunidade:

```go
func TestPrazo(t *testing.T) {
    casos := []struct{
        nome string
        entrada time.Time
        querErro bool
    }{
        {"data futura", amanha, false},
        {"data passada", ontem, true},
    }
    for _, c := range casos {
        t.Run(c.nome, func(t *testing.T) { /* ... */ })
    }
}
```

`t.Run` dá nome a cada caso, então a falha diz qual cenário quebrou.
`httptest` cobre handler sem subir servidor.

Rode com `-race` sempre. Corrida que não aparece hoje aparece em produção.

## CI

```yaml
      - uses: actions/setup-go@v5
        with:
          go-version: '1.23'
          cache: true
      - run: go vet ./...
      - run: go test -race ./...
```

Ajuste `go-version` para a que você tem instalada — deve bater com a linha `go`
do `go.mod`.

## .gitignore

```
bin/
*.exe
.env
coverage.out
```
