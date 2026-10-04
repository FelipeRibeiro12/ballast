package http

import (
	"context"
	"go/ast"
	"go/parser"
	"go/token"
	"io"
	"net/http"
	"net/http/httptest"
	"sort"
	"strconv"
	"strings"
	"testing"

	"github.com/getkin/kin-openapi/openapi3"
	"github.com/getkin/kin-openapi/openapi3filter"
	"github.com/getkin/kin-openapi/routers/legacy"
	"github.com/jackc/pgx/v5/pgxpool"
)

const specPath = "../../openapi.yaml"

func loadSpec(t *testing.T) *openapi3.T {
	t.Helper()
	loader := openapi3.NewLoader()
	spec, err := loader.LoadFromFile(specPath)
	if err != nil {
		t.Fatalf("carrega %s: %v", specPath, err)
	}
	if err := spec.Validate(context.Background()); err != nil {
		t.Fatalf("spec inválida: %v", err)
	}
	return spec
}

// O ServeMux não permite enumerar as rotas registradas, então lemos os
// literais passados a Handle/HandleFunc em router.go. Registro com padrão não
// literal falha o teste, para não deixar uma rota escapar da conferência.
func registeredRoutes(t *testing.T) []string {
	t.Helper()
	file, err := parser.ParseFile(token.NewFileSet(), "router.go", nil, 0)
	if err != nil {
		t.Fatalf("lê router.go: %v", err)
	}
	var routes []string
	ast.Inspect(file, func(n ast.Node) bool {
		call, ok := n.(*ast.CallExpr)
		if !ok {
			return true
		}
		sel, ok := call.Fun.(*ast.SelectorExpr)
		if !ok || (sel.Sel.Name != "Handle" && sel.Sel.Name != "HandleFunc") {
			return true
		}
		lit, ok := call.Args[0].(*ast.BasicLit)
		if !ok || lit.Kind != token.STRING {
			t.Errorf("router.go: padrão de rota não literal em %s; o teste de aderência não consegue conferir", sel.Sel.Name)
			return true
		}
		pattern, _ := strconv.Unquote(lit.Value)
		routes = append(routes, pattern)
		return true
	})
	if len(routes) == 0 {
		t.Fatal("nenhuma rota encontrada em router.go: a varredura está quebrada")
	}
	sort.Strings(routes)
	return routes
}

func splitPattern(t *testing.T, pattern string) (method, path string) {
	t.Helper()
	method, path, ok := strings.Cut(pattern, " ")
	if !ok {
		t.Fatalf("rota %q sem método: toda rota deve declarar o método para constar na spec", pattern)
	}
	return method, path
}

func TestRotasEstaoNaSpec(t *testing.T) {
	spec := loadSpec(t)

	registered := map[string]bool{}
	for _, pattern := range registeredRoutes(t) {
		method, path := splitPattern(t, pattern)
		registered[method+" "+path] = true
		item := spec.Paths.Value(path)
		if item == nil || item.GetOperation(method) == nil {
			t.Errorf("rota %q registrada no router e ausente de openapi.yaml", pattern)
		}
	}

	// Sentido inverso: a spec não pode prometer rota que o router não tem.
	for path, item := range spec.Paths.Map() {
		for method := range item.Operations() {
			if !registered[method+" "+path] {
				t.Errorf("%s %s consta em openapi.yaml e não está registrada no router", method, path)
			}
		}
	}
}

func poolIndisponivel(t *testing.T) *pgxpool.Pool {
	t.Helper()
	// Porta 1 recusa conexão; o pool é lazy, então o erro só aparece na query.
	pool, err := pgxpool.New(context.Background(),
		"postgres://usuario:senha@127.0.0.1:1/ballast?sslmode=disable&connect_timeout=1")
	if err != nil {
		t.Fatalf("cria pool: %v", err)
	}
	t.Cleanup(pool.Close)
	return pool
}

func TestRespostasSeguemASpec(t *testing.T) {
	spec := loadSpec(t)
	router, err := legacy.NewRouter(spec)
	if err != nil {
		t.Fatalf("monta roteador da spec: %v", err)
	}

	// Cada rota registrada precisa declarar aqui os status que a spec descreve.
	cases := map[string]map[int]func(*testing.T) *pgxpool.Pool{
		"GET /healthz": {
			http.StatusOK:                 poolDeTeste,
			http.StatusServiceUnavailable: poolIndisponivel,
		},
	}

	for _, pattern := range registeredRoutes(t) {
		statuses, ok := cases[pattern]
		if !ok {
			t.Errorf("rota %q sem caso de resposta neste teste", pattern)
			continue
		}
		method, path := splitPattern(t, pattern)
		for status, newPool := range statuses {
			t.Run(pattern+" "+strconv.Itoa(status), func(t *testing.T) {
				pool := newPool(t)

				req := httptest.NewRequest(method, path, nil)
				rec := httptest.NewRecorder()
				NewRouter(pool).ServeHTTP(rec, req)

				if rec.Code != status {
					t.Fatalf("status = %d, want %d", rec.Code, status)
				}

				route, pathParams, err := router.FindRoute(req)
				if err != nil {
					t.Fatalf("rota fora da spec: %v", err)
				}
				input := &openapi3filter.ResponseValidationInput{
					RequestValidationInput: &openapi3filter.RequestValidationInput{
						Request:    req,
						PathParams: pathParams,
						Route:      route,
					},
					Status: rec.Code,
					Header: rec.Header(),
				}
				input.SetBodyBytes(rec.Body.Bytes())
				if err := openapi3filter.ValidateResponse(context.Background(), input); err != nil {
					body, _ := io.ReadAll(rec.Body)
					t.Errorf("resposta não segue a spec: %v (corpo: %s)", err, body)
				}
			})
		}
	}
}
