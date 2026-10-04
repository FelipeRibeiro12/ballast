package http

import (
	"context"
	"go/ast"
	"go/parser"
	"go/token"
	"io"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"testing"

	"github.com/FelipeRibeiro12/ballast/internal/identidade"
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
// literais passados a Handle/HandleFunc em todos os .go não-teste do pacote.
// Registro com padrão não literal falha o teste, para não deixar uma rota
// escapar da conferência.
func registeredRoutes(t *testing.T) []string {
	t.Helper()
	arquivos, err := filepath.Glob("*.go")
	if err != nil {
		t.Fatalf("lista arquivos do pacote: %v", err)
	}
	var routes []string
	fset := token.NewFileSet()
	for _, arquivo := range arquivos {
		if strings.HasSuffix(arquivo, "_test.go") {
			continue
		}
		file, err := parser.ParseFile(fset, arquivo, nil, 0)
		if err != nil {
			t.Fatalf("lê %s: %v", arquivo, err)
		}
		ast.Inspect(file, func(n ast.Node) bool {
			call, ok := n.(*ast.CallExpr)
			if !ok {
				return true
			}
			sel, ok := call.Fun.(*ast.SelectorExpr)
			if !ok || (sel.Sel.Name != "Handle" && sel.Sel.Name != "HandleFunc") || len(call.Args) == 0 {
				return true
			}
			lit, ok := call.Args[0].(*ast.BasicLit)
			if !ok || lit.Kind != token.STRING {
				t.Errorf("%s: padrão de rota não literal em %s; o teste de aderência não consegue conferir", arquivo, sel.Sel.Name)
				return true
			}
			pattern, _ := strconv.Unquote(lit.Value)
			routes = append(routes, pattern)
			return true
		})
	}
	if len(routes) == 0 {
		t.Fatal("nenhuma rota encontrada nos .go do pacote: a varredura está quebrada")
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

// Cada caso exercita uma rota até produzir o status indicado e devolve a
// requisição e a resposta, para o teste conferir contra a spec.
type casoDeResposta func(t *testing.T) (*http.Request, *httptest.ResponseRecorder)

func casosDeResposta() map[string]map[int]casoDeResposta {
	return map[string]map[int]casoDeResposta{
		"GET /auth/eu": {
			http.StatusOK: func(t *testing.T) (*http.Request, *httptest.ResponseRecorder) {
				a := novoAmbiente(t, true)
				_, c := a.sessao(t)
				return getComCookie(a.h, "/auth/eu", c)
			},
			http.StatusUnauthorized: func(t *testing.T) (*http.Request, *httptest.ResponseRecorder) {
				a := novoAmbiente(t, true)
				return getComCookie(a.h, "/auth/eu", nil)
			},
			http.StatusInternalServerError: func(t *testing.T) (*http.Request, *httptest.ResponseRecorder) {
				pool := poolIndisponivel(t)
				h := NewRouter(pool, identidade.NovoServico(pool), true)
				return getComCookie(h, "/auth/eu", &http.Cookie{Name: "ballast_sessao", Value: "x"})
			},
		},
		"POST /auth/logout": {
			http.StatusNoContent: func(t *testing.T) (*http.Request, *httptest.ResponseRecorder) {
				a := novoAmbiente(t, true)
				_, c := a.sessao(t)
				return postComCookie(a.h, "/auth/logout", c)
			},
			http.StatusInternalServerError: func(t *testing.T) (*http.Request, *httptest.ResponseRecorder) {
				pool := poolIndisponivel(t)
				h := NewRouter(pool, identidade.NovoServico(pool), true)
				return postComCookie(h, "/auth/logout", &http.Cookie{Name: "ballast_sessao", Value: "x"})
			},
		},
		"POST /auth/login": {
			http.StatusOK: func(t *testing.T) (*http.Request, *httptest.ResponseRecorder) {
				a, email := ambienteComUsuario(t)
				return postJSON(a.h, "/auth/login", map[string]string{"email": email, "senha": senhaDeTeste})
			},
			http.StatusBadRequest: func(t *testing.T) (*http.Request, *httptest.ResponseRecorder) {
				a := novoAmbiente(t, true)
				return postJSON(a.h, "/auth/login", `{"email":"x"}`)
			},
			http.StatusUnauthorized: func(t *testing.T) (*http.Request, *httptest.ResponseRecorder) {
				a := novoAmbiente(t, true)
				return postJSON(a.h, "/auth/login", map[string]string{"email": emailUnico(t, nil), "senha": senhaDeTeste})
			},
			http.StatusUnsupportedMediaType: func(t *testing.T) (*http.Request, *httptest.ResponseRecorder) {
				a := novoAmbiente(t, true)
				req := httptest.NewRequest(http.MethodPost, "/auth/login", strings.NewReader("email=x"))
				req.Header.Set("Content-Type", "text/plain")
				rec := httptest.NewRecorder()
				a.h.ServeHTTP(rec, req)
				return req, rec
			},
			http.StatusInternalServerError: func(t *testing.T) (*http.Request, *httptest.ResponseRecorder) {
				pool := poolIndisponivel(t)
				h := NewRouter(pool, identidade.NovoServico(pool), true)
				return postJSON(h, "/auth/login", map[string]string{"email": "a@exemplo.test", "senha": senhaDeTeste})
			},
		},
		"POST /auth/cadastro": {
			http.StatusCreated: func(t *testing.T) (*http.Request, *httptest.ResponseRecorder) {
				a := novoAmbiente(t, true)
				return postJSON(a.h, "/auth/cadastro", map[string]string{"email": emailUnico(t, a.pool), "senha": senhaDeTeste, "nome": "Fulano"})
			},
			http.StatusBadRequest: func(t *testing.T) (*http.Request, *httptest.ResponseRecorder) {
				a := novoAmbiente(t, true)
				return postJSON(a.h, "/auth/cadastro", `{"email":"x"}`)
			},
			http.StatusNotFound: func(t *testing.T) (*http.Request, *httptest.ResponseRecorder) {
				a := novoAmbiente(t, false)
				return postJSON(a.h, "/auth/cadastro", map[string]string{"email": emailUnico(t, a.pool), "senha": senhaDeTeste})
			},
			http.StatusConflict: func(t *testing.T) (*http.Request, *httptest.ResponseRecorder) {
				a := novoAmbiente(t, true)
				corpo := map[string]string{"email": emailUnico(t, a.pool), "senha": senhaDeTeste}
				postJSON(a.h, "/auth/cadastro", corpo)
				return postJSON(a.h, "/auth/cadastro", corpo)
			},
			http.StatusUnsupportedMediaType: func(t *testing.T) (*http.Request, *httptest.ResponseRecorder) {
				a := novoAmbiente(t, true)
				req := httptest.NewRequest(http.MethodPost, "/auth/cadastro", strings.NewReader("email=x"))
				req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
				rec := httptest.NewRecorder()
				a.h.ServeHTTP(rec, req)
				return req, rec
			},
			http.StatusInternalServerError: func(t *testing.T) (*http.Request, *httptest.ResponseRecorder) {
				pool := poolIndisponivel(t)
				h := NewRouter(pool, identidade.NovoServico(pool), true)
				return postJSON(h, "/auth/cadastro", map[string]string{"email": "a@exemplo.test", "senha": senhaDeTeste})
			},
		},
		"GET /healthz": {
			http.StatusOK:                 casoHealth(poolDeTeste),
			http.StatusServiceUnavailable: casoHealth(poolIndisponivel),
		},
	}
}

func casoHealth(newPool func(*testing.T) *pgxpool.Pool) casoDeResposta {
	return func(t *testing.T) (*http.Request, *httptest.ResponseRecorder) {
		req := httptest.NewRequest(http.MethodGet, "/healthz", nil)
		rec := httptest.NewRecorder()
		NewRouter(newPool(t), nil, false).ServeHTTP(rec, req)
		return req, rec
	}
}

// Os status esperados vêm da spec: status declarado sem caso falha o teste, e
// caso para status que a spec não declara também.
func TestRespostasSeguemASpec(t *testing.T) {
	spec := loadSpec(t)
	router, err := legacy.NewRouter(spec)
	if err != nil {
		t.Fatalf("monta roteador da spec: %v", err)
	}
	casos := casosDeResposta()

	for _, pattern := range registeredRoutes(t) {
		method, path := splitPattern(t, pattern)
		item := spec.Paths.Value(path)
		if item == nil || item.GetOperation(method) == nil {
			continue // TestRotasEstaoNaSpec já reporta
		}
		responses := item.GetOperation(method).Responses.Map()

		esperados := map[int]bool{}
		for codigo := range responses {
			status, err := strconv.Atoi(codigo)
			if err != nil {
				t.Errorf("%s: resposta %q da spec não é um status numérico; o teste não deriva casos de default nem de faixas", pattern, codigo)
				continue
			}
			esperados[status] = true
			if casos[pattern][status] == nil {
				t.Errorf("%s: status %d consta na spec e nenhum caso o exercita", pattern, status)
			}
		}
		for status := range casos[pattern] {
			if !esperados[status] {
				t.Errorf("%s: caso para o status %d, que a spec não declara", pattern, status)
			}
		}

		for status, caso := range casos[pattern] {
			t.Run(pattern+" "+strconv.Itoa(status), func(t *testing.T) {
				req, rec := caso(t)
				if rec.Code != status {
					t.Fatalf("status = %d, want %d (corpo: %s)", rec.Code, status, rec.Body.String())
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

func ambienteComUsuario(t *testing.T) (*ambiente, string) {
	t.Helper()
	a := novoAmbiente(t, true)
	email := emailUnico(t, a.pool)
	a.cadastra(t, email)
	return a, email
}

// A spec descreve o Set-Cookie do login e o servidor emite exatamente os
// atributos de segurança prometidos (ADR-015).
func TestLoginDefineCookieDeSessaoComOsAtributosDeSeguranca(t *testing.T) {
	spec := loadSpec(t)
	resp := spec.Paths.Value("/auth/login").GetOperation(http.MethodPost).Responses.Value("200").Value
	if resp.Headers["Set-Cookie"] == nil {
		t.Error("a resposta 200 de /auth/login não documenta o Set-Cookie na spec")
	}

	a, email := ambienteComUsuario(t)
	_, rec := postJSON(a.h, "/auth/login", map[string]string{"email": email, "senha": senhaDeTeste})

	linhas := rec.Header().Values("Set-Cookie")
	if len(linhas) != 1 {
		t.Fatalf("Set-Cookie = %v, want exatamente um", linhas)
	}
	c := cookieDe(rec, "ballast_sessao")
	if c == nil {
		t.Fatalf("cookie ballast_sessao ausente: %v", linhas)
	}
	if !c.HttpOnly {
		t.Error("cookie sem HttpOnly")
	}
	if !c.Secure {
		t.Error("cookie sem Secure")
	}
	if c.SameSite != http.SameSiteLaxMode {
		t.Errorf("SameSite = %v, want Lax", c.SameSite)
	}
	if c.Path != "/" {
		t.Errorf("Path = %q, want /", c.Path)
	}
	if c.Domain != "" {
		t.Errorf("Domain = %q: cookie sem Domain fica preso ao host de origem", c.Domain)
	}
	// 7 dias absolutos; a diferença de segundos entre gravar e responder é tolerada.
	if c.MaxAge < 7*24*3600-10 || c.MaxAge > 7*24*3600 {
		t.Errorf("Max-Age = %d, want ~604800", c.MaxAge)
	}
	if !strings.Contains(linhas[0], "Max-Age=") {
		t.Errorf("Set-Cookie sem Max-Age explícito: %s", linhas[0])
	}
}
