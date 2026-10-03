---
paths:
  - "api/**/*.go"
---

# Go

## Erros

- **Erro é valor.** Trate na hora ou embrulhe com contexto:
  `fmt.Errorf("busca remessa %s: %w", id, err)`. O `%w` preserva a cadeia para
  `errors.Is` e `errors.As`.
- Nunca descarte erro com `_`. Se realmente não importa, comente por quê.
- Erro sentinela (`var ErrNaoEncontrado = errors.New(...)`) quando quem chama
  precisa distinguir o caso.
- `panic` só em falha de inicialização, nunca em handler ou em biblioteca.

## Estrutura

- `cmd/<binário>/` só monta e sobe. Lógica não mora lá.
- `internal/` para tudo que não é API pública — o compilador garante a
  fronteira.
- Sem `pkg/` se não há consumidor externo.
- Pacote nomeado pelo que ele é (`store`, `domain`), nunca `utils`, `helpers`
  ou `common`.

## Idioma

- **Aceite interface, devolva struct.** Defina a interface no pacote que
  consome, não no que implementa.
- Interface pequena: uma ou duas funções.
- `context.Context` como primeiro parâmetro em tudo que faz I/O, e respeite o
  cancelamento.
- Zero value útil sempre que possível: struct que funciona sem construtor.
- `defer` para liberar recurso, na linha seguinte à aquisição.
- Sem estado global mutável.
- Concorrência só quando resolve um problema real. Toda goroutine precisa de um
  fim claro — goroutine sem quem a encerre é vazamento.

## Teste

- `arquivo_test.go`, mesmo pacote, sem framework: `testing` basta.
- Tabela de casos com `t.Run`, para a falha dizer qual cenário quebrou.
- `httptest` para handler, sem subir servidor.
- **Sempre `-race`.** Corrida que não aparece hoje aparece em produção.

## Comandos

```bash
go build ./...
go test -race ./...
go vet ./...
gofmt -l .              # vazio = formatado
golangci-lint run
```
