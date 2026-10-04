# Teste F0.5 — OpenAPI como fonte da verdade do contrato

## Escopo testado

- Spec OpenAPI (`api/openapi.yaml`) como contrato único para `/healthz`
- Teste de aderência em Go com `kin-openapi` validando rotas e respostas
- Geração de tipos TypeScript (`web/src/api/schema.d.ts`) com `openapi-typescript`
- Gate do CI que falha se tipos TS divergem da spec
- Suíte de testes Go, web e lint
- Segurança de dependências (kin-openapi fora de código de produção)
- Segredos nos arquivos novos

## Achados

### Aderência de rotas: OK

**Como reproduzir:**
1. Adicionar rota no router sem documentá-la na spec:
   - Editar `api/internal/http/router.go` e registrar `mux.HandleFunc("GET /secret", ...)`
   - Rodar `DATABASE_URL=... go test -race ./...` em `api/`
2. Adicionar rota na spec sem implementar no router:
   - Editar `api/openapi.yaml` e adicionar path `/undocumented`
   - Rodar `DATABASE_URL=... go test -race ./...`

**O que acontece:**
- Teste `TestRotasEstaoNaSpec` em `api/internal/http/openapi_test.go` (linhas 81-101) falha imediatamente
- Mensagens de erro claras: `rota "GET /secret" registrada no router e ausente de openapi.yaml` ou `GET /undocumented consta em openapi.yaml e não está registrada no router`

**Resultado:** OK

---

### Validação de resposta: OK

**Campos extras rejeitados:**
- Spec define `additionalProperties: false` para ambas as respostas
- Teste rodado: handler alterado para devolver `{"mensagem": "ok", "extra": "campo"}`
- Resultado: `TestRespostasSeguemASpec` falha com `property "extra" is unsupported`

**Content-Type errado rejeitado:**
- Spec exige `application/json`
- Teste rodado: handler alterado para devolver `text/plain`
- Resultado: falha com `response header Content-Type has unexpected value: "text/plain"`

**Campo obrigatório faltando rejeitado:**
- Spec marca `mensagem` como required
- Teste rodado: handler alterado para devolver `{}`
- Resultado: falha com `property "mensagem" is missing`

**Resultado:** OK

---

### Status codes adicionais não validados automaticamente: MENOR

**Como reproduzir:**
1. Editar `api/openapi.yaml` e adicionar resposta `500` com schema:
   ```yaml
   "500":
     description: Erro interno
     content:
       application/json:
         schema:
           type: object
           required: [erro]
           additionalProperties: false
           properties:
             erro:
               type: string
   ```
2. Rodar `DATABASE_URL=... go test -race ./... -run TestRespostas`

**O que acontece:**
- Teste passa (nenhuma falha)
- Motivo: tabela de casos em linhas 124-129 de `openapi_test.go` é manual
  ```go
  cases := map[string]map[int]func(*testing.T) *pgxpool.Pool{
      "GET /healthz": {
          http.StatusOK:                 poolDeTeste,
          http.StatusServiceUnavailable: poolIndisponivel,
      },
  }
  ```
- Se alguém adiciona status à spec mas esquece de adicionar à tabela `cases`, a resposta HTTP 500 nunca é testada contra a spec

**O que deveria acontecer:**
- Teste falha exigindo que todo status code na spec tenha um caso de teste correspondente, OU
- Teste valida automaticamente todos os status codes da spec contra o handler real

**Gravidade:** Menor (falso verde possível — adição futura de status sem teste correspondente)

---

### Gate do CI: OK

**Determinismo de geração:**
- Rodado `pnpm generate:api` 3x consecutivas em `web/`
- Hashes MD5 do `schema.d.ts`: `89aec151450aea96236da7ea492868ea` (todas 3x idênticas)

**Divergência detectada após alteração da spec:**
- Editado `api/openapi.yaml` para adicionar field `timestamp` em resposta 200
- Rodado `pnpm generate:api` seguido de `git status --porcelain -- src/api`
- Resultado: arquivo modificado detectado, diff mostrado, gate teria falhado

**Arquivo apagado regenerado:**
- Deletado `web/src/api/schema.d.ts`
- Rodado `pnpm generate:api`
- Resultado: arquivo regenerado corretamente, `git status` limpo

**Geração funciona de outro diretório:**
- Rodado `pnpm --dir web generate:api` da raiz do repo
- Resultado: OK, arquivo em sincronismo

**Gravidade:** OK (gate funciona ✅)

---

### Isolamento de dependências: OK

- `grep -r "getkin/kin-openapi" api --include="*.go" | grep -v "_test.go"` retorna vazio
- `kin-openapi` aparece apenas em imports de `api/internal/http/openapi_test.go`
- `go.mod` inclui dependência, mas confinada a testes

**Gravidade:** OK (segurança de dependência ✅)

---

### Segredos em arquivos novos: OK

- Arquivos novos: `api/openapi.yaml`, `web/src/api/schema.d.ts`, `.github/workflows/ci.yml` (parcial), `api/go.mod` (parcial), `api/go.sum` (parcial), `web/package.json` (parcial)
- String `senha` aparece uma vez em `api/internal/http/openapi_test.go:108` mas é credencial fake em teste
- Nenhum token, chave privada ou credencial real encontrado

**Gravidade:** OK (sem segredos ✅)

---

### Suíte completa: OK

- `make test` (Go): 2 pacotes, todos verdes
- `pnpm test` (web): 6 testes, todos verdes
- `pnpm build` (web): constrói sem erros, inclui geração de tipos
- `pnpm install --frozen-lockfile`: OK
- `go vet ./...`: OK
- `pnpm lint`: OK

---

## Recomendação

**Liberar com dívida técnica registrada.**

A fatia F0.5 implementa corretamente a aderência de rotas e validação de respostas contra a spec OpenAPI. O gate do CI é funcional e determinístico. Tudo que foi pedido funciona.

A lacuna encontrada (validação automática de status codes adicionados à spec) é menor e apropriada para uma dívida futura, pois requer uma mudança no padrão de teste:
- Opção 1: Iterar sobre todos os status codes da spec e exigir um caso de teste para cada um
- Opção 2: Usar uma técnica automática de geração de testes a partir da spec (mais complexo)

Nada disso é bloqueante para o lançamento. O risco é mitigável: novas respostas exigem mudança na spec + commit, e CI pode lembrar via revisão que `cases` precisa ser atualizada.

**Artefatos:**
- 4 commits de implementação (e29caf0, 61e5198, f781f2d, 65d974a), todos verdes
- Prova do gate no CI real, feita pelo tech-lead com commits descartáveis já revertidos: a 1ª quebra (`required` sem a propriedade) fez o job `api` falhar e o `web` ficou verde, porque o tipo gerado não muda; a 2ª (com a propriedade `versao`) fez o job `web` falhar no passo "cliente TS em dia com o OpenAPI"
- Teste de aderência: ~170 linhas em `api/internal/http/openapi_test.go`
- Geração de tipos: integrada no script `build` do web
- Gate do CI: bem colocado, roda antes do build
