package http

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/FelipeRibeiro12/ballast/internal/identidade"
	"github.com/jackc/pgx/v5/pgxpool"
)

const senhaDeTeste = "senha-de-teste-12345"

// Integração com o Postgres real e o serviço de identidade de verdade: é a
// única composição que prova que o SQL, o hash e o handler funcionam juntos.
type ambiente struct {
	pool *pgxpool.Pool
	h    http.Handler
}

func novoAmbiente(t *testing.T, registroAberto bool) *ambiente {
	t.Helper()
	pool := poolDeTeste(t)
	return &ambiente{pool: pool, h: NewRouter(pool, identidade.NovoServico(pool), registroAberto)}
}

func emailUnico(t *testing.T, pool *pgxpool.Pool) string {
	t.Helper()
	var b [8]byte
	if _, err := rand.Read(b[:]); err != nil {
		t.Fatal(err)
	}
	email := "teste-" + hex.EncodeToString(b[:]) + "@exemplo.test"
	if pool != nil {
		t.Cleanup(func() {
			// A sessão some junto, por causa do ON DELETE CASCADE.
			if _, err := pool.Exec(context.Background(), "DELETE FROM usuario WHERE email = $1", email); err != nil {
				t.Errorf("limpa usuário de teste: %v", err)
			}
		})
	}
	return email
}

func postJSON(h http.Handler, path string, corpo any) (*http.Request, *httptest.ResponseRecorder) {
	var raw []byte
	switch c := corpo.(type) {
	case string:
		raw = []byte(c)
	default:
		raw, _ = json.Marshal(c)
	}
	req := httptest.NewRequest(http.MethodPost, path, bytes.NewReader(raw))
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	return req, rec
}

func capturaLog(t *testing.T) *bytes.Buffer {
	t.Helper()
	var buf bytes.Buffer
	anterior := slog.Default()
	slog.SetDefault(slog.New(slog.NewTextHandler(&buf, nil)))
	t.Cleanup(func() { slog.SetDefault(anterior) })
	return &buf
}

type usuarioResposta struct {
	ID    string  `json:"id"`
	Email string  `json:"email"`
	Nome  *string `json:"nome"`
}

func TestCadastroCriaUsuario(t *testing.T) {
	a := novoAmbiente(t, true)
	email := emailUnico(t, a.pool)
	logBuf := capturaLog(t)

	_, rec := postJSON(a.h, "/auth/cadastro", map[string]string{"email": email, "senha": senhaDeTeste, "nome": "Fulano"})

	if rec.Code != http.StatusCreated {
		t.Fatalf("status = %d, want 201 (corpo: %s)", rec.Code, rec.Body)
	}
	var u usuarioResposta
	if err := json.Unmarshal(rec.Body.Bytes(), &u); err != nil {
		t.Fatalf("corpo não é JSON: %v", err)
	}
	if u.Email != email || u.Nome == nil || *u.Nome != "Fulano" || len(u.ID) != 36 {
		t.Errorf("resposta = %+v", u)
	}

	var idNoBanco, hash string
	err := a.pool.QueryRow(context.Background(), "SELECT id::text, senha_hash FROM usuario WHERE email = $1", email).Scan(&idNoBanco, &hash)
	if err != nil {
		t.Fatalf("usuário não gravado: %v", err)
	}
	if idNoBanco != u.ID {
		t.Errorf("id no banco = %s, resposta = %s", idNoBanco, u.ID)
	}
	if !strings.HasPrefix(hash, "$argon2id$") || strings.Contains(hash, senhaDeTeste) {
		t.Errorf("senha_hash inesperado: %q", hash)
	}

	if strings.Contains(rec.Body.String(), senhaDeTeste) || strings.Contains(rec.Body.String(), "argon2") {
		t.Error("resposta vaza senha ou hash")
	}
	if strings.Contains(logBuf.String(), senhaDeTeste) || strings.Contains(logBuf.String(), "argon2") {
		t.Error("log vaza senha ou hash")
	}
	if strings.Contains(logBuf.String(), email) {
		t.Error("log contém o e-mail completo")
	}
	if logBuf.Len() == 0 {
		t.Error("cadastro não foi registrado no log")
	}
}

func TestCadastroSemNomeDevolveNomeNulo(t *testing.T) {
	a := novoAmbiente(t, true)
	_, rec := postJSON(a.h, "/auth/cadastro", map[string]string{"email": emailUnico(t, a.pool), "senha": senhaDeTeste})
	if rec.Code != http.StatusCreated {
		t.Fatalf("status = %d (corpo: %s)", rec.Code, rec.Body)
	}
	if !strings.Contains(rec.Body.String(), `"nome":null`) {
		t.Errorf("corpo = %s, want nome nulo", rec.Body)
	}
}

func TestCadastroFechadoDevolve404IgualARotaInexistente(t *testing.T) {
	a := novoAmbiente(t, false)
	email := emailUnico(t, a.pool)

	_, rec := postJSON(a.h, "/auth/cadastro", map[string]string{"email": email, "senha": senhaDeTeste})
	_, inexistente := postJSON(a.h, "/auth/nao-existe", map[string]string{})

	if rec.Code != http.StatusNotFound {
		t.Fatalf("status = %d, want 404", rec.Code)
	}
	if rec.Body.String() != inexistente.Body.String() || rec.Header().Get("Content-Type") != inexistente.Header().Get("Content-Type") {
		t.Errorf("404 do cadastro (%q, %q) difere do de rota inexistente (%q, %q)",
			rec.Body, rec.Header().Get("Content-Type"), inexistente.Body, inexistente.Header().Get("Content-Type"))
	}
	var n int
	if err := a.pool.QueryRow(context.Background(), "SELECT count(*) FROM usuario WHERE email = $1", email).Scan(&n); err != nil || n != 0 {
		t.Errorf("cadastro fechado gravou usuário (n=%d, err=%v)", n, err)
	}
}

func TestCadastroFechadoNaoDependeDoCorpo(t *testing.T) {
	a := novoAmbiente(t, false)
	// Mesmo com content-type errado, a rota fechada responde 404 e não 415.
	req := httptest.NewRequest(http.MethodPost, "/auth/cadastro", strings.NewReader("x"))
	req.Header.Set("Content-Type", "text/plain")
	rec := httptest.NewRecorder()
	a.h.ServeHTTP(rec, req)
	if rec.Code != http.StatusNotFound {
		t.Errorf("status = %d, want 404", rec.Code)
	}
}

func TestCadastroRejeitaCampoDesconhecido(t *testing.T) {
	a := novoAmbiente(t, true)
	email := emailUnico(t, a.pool)
	_, rec := postJSON(a.h, "/auth/cadastro", `{"email":"`+email+`","senha":"`+senhaDeTeste+`","admin":true}`)
	if rec.Code != http.StatusBadRequest {
		t.Errorf("status = %d, want 400", rec.Code)
	}
}

func TestCadastroRejeitaContentTypeQueNaoSejaJSON(t *testing.T) {
	a := novoAmbiente(t, true)
	for _, ct := range []string{"text/plain", "application/x-www-form-urlencoded", ""} {
		req := httptest.NewRequest(http.MethodPost, "/auth/cadastro", strings.NewReader(`{}`))
		if ct != "" {
			req.Header.Set("Content-Type", ct)
		}
		rec := httptest.NewRecorder()
		a.h.ServeHTTP(rec, req)
		if rec.Code != http.StatusUnsupportedMediaType {
			t.Errorf("content-type %q: status = %d, want 415", ct, rec.Code)
		}
	}
}

func TestCadastroAceitaCharsetNoContentType(t *testing.T) {
	a := novoAmbiente(t, true)
	body, _ := json.Marshal(map[string]string{"email": emailUnico(t, a.pool), "senha": senhaDeTeste})
	req := httptest.NewRequest(http.MethodPost, "/auth/cadastro", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json; charset=utf-8")
	rec := httptest.NewRecorder()
	a.h.ServeHTTP(rec, req)
	if rec.Code != http.StatusCreated {
		t.Errorf("status = %d, want 201 (corpo: %s)", rec.Code, rec.Body)
	}
}

func TestCadastroValidaEntrada(t *testing.T) {
	a := novoAmbiente(t, true)
	longo := func(n int) string { return strings.Repeat("a", n) }

	casos := map[string]string{
		"senha de 11 caracteres":  `{"email":"a@exemplo.test","senha":"` + longo(11) + `"}`,
		"senha de 129 caracteres": `{"email":"a@exemplo.test","senha":"` + longo(129) + `"}`,
		"senha ausente":           `{"email":"a@exemplo.test"}`,
		"e-mail ausente":          `{"senha":"` + senhaDeTeste + `"}`,
		"e-mail sem arroba":       `{"email":"exemplo.test","senha":"` + senhaDeTeste + `"}`,
		"e-mail sem domínio":      `{"email":"a@","senha":"` + senhaDeTeste + `"}`,
		"e-mail com espaço":       `{"email":"a b@exemplo.test","senha":"` + senhaDeTeste + `"}`,
		"e-mail de 255":           `{"email":"` + longo(255-len("@exemplo.test")) + `@exemplo.test","senha":"` + senhaDeTeste + `"}`,
		"nome de 101":             `{"email":"a@exemplo.test","senha":"` + senhaDeTeste + `","nome":"` + longo(101) + `"}`,
		"tipo errado":             `{"email":1,"senha":"` + senhaDeTeste + `"}`,
		"json quebrado":           `{"email":`,
		"dois documentos":         `{"email":"a@exemplo.test","senha":"` + senhaDeTeste + `"}{}`,
		"corpo acima de 8 KiB":    `{"email":"a@exemplo.test","senha":"` + longo(9000) + `"}`,
		"vazio":                   ``,
	}
	for nome, corpo := range casos {
		t.Run(nome, func(t *testing.T) {
			_, rec := postJSON(a.h, "/auth/cadastro", corpo)
			if rec.Code != http.StatusBadRequest {
				t.Errorf("status = %d, want 400 (corpo: %s)", rec.Code, rec.Body)
			}
			var erro struct {
				Erro string `json:"erro"`
			}
			if err := json.Unmarshal(rec.Body.Bytes(), &erro); err != nil || erro.Erro == "" {
				t.Errorf("corpo de erro fora do formato: %s", rec.Body)
			}
		})
	}
}

func TestCadastroAceitaSenhaNosLimites(t *testing.T) {
	a := novoAmbiente(t, true)
	for _, tam := range []int{12, 128} {
		_, rec := postJSON(a.h, "/auth/cadastro", map[string]string{
			"email": emailUnico(t, a.pool), "senha": strings.Repeat("a", tam)})
		if rec.Code != http.StatusCreated {
			t.Errorf("senha de %d caracteres: status = %d, want 201 (corpo: %s)", tam, rec.Code, rec.Body)
		}
	}
}

func TestCadastroNormalizaEmailEBarraDuplicadoComOutraCaixa(t *testing.T) {
	a := novoAmbiente(t, true)
	minusculo := emailUnico(t, a.pool)
	misto := "  " + strings.ToUpper(minusculo[:3]) + minusculo[3:] + " "

	_, rec := postJSON(a.h, "/auth/cadastro", map[string]string{"email": misto, "senha": senhaDeTeste})
	if rec.Code != http.StatusCreated {
		t.Fatalf("status = %d, want 201 (corpo: %s)", rec.Code, rec.Body)
	}
	var u usuarioResposta
	_ = json.Unmarshal(rec.Body.Bytes(), &u)
	if u.Email != minusculo {
		t.Errorf("e-mail na resposta = %q, want %q", u.Email, minusculo)
	}
	var gravado string
	if err := a.pool.QueryRow(context.Background(), "SELECT email FROM usuario WHERE id = $1", u.ID).Scan(&gravado); err != nil || gravado != minusculo {
		t.Errorf("e-mail gravado = %q (err=%v), want %q", gravado, err, minusculo)
	}

	logBuf := capturaLog(t)
	_, rec = postJSON(a.h, "/auth/cadastro", map[string]string{"email": minusculo, "senha": senhaDeTeste})
	if rec.Code != http.StatusConflict {
		t.Errorf("segundo cadastro: status = %d, want 409", rec.Code)
	}
	if strings.Contains(logBuf.String(), minusculo) {
		t.Error("log da falha contém o e-mail completo")
	}
	if !strings.Contains(logBuf.String(), "t***@exemplo.test") {
		t.Errorf("log da falha sem e-mail mascarado: %s", logBuf)
	}
}

func TestCadastroComBancoIndisponivelNaoVazaDetalhe(t *testing.T) {
	pool := poolIndisponivel(t)
	h := NewRouter(pool, identidade.NovoServico(pool), true)
	logBuf := capturaLog(t)

	_, rec := postJSON(h, "/auth/cadastro", map[string]string{"email": "a@exemplo.test", "senha": senhaDeTeste})

	if rec.Code != http.StatusInternalServerError {
		t.Fatalf("status = %d, want 500", rec.Code)
	}
	for _, proibido := range []string{"127.0.0.1", "usuario", "dial", "connect", "postgres", "ballast"} {
		if strings.Contains(rec.Body.String(), proibido) {
			t.Errorf("corpo vaza %q: %s", proibido, rec.Body)
		}
	}
	if strings.Contains(logBuf.String(), senhaDeTeste) {
		t.Error("log contém a senha")
	}
	if logBuf.Len() == 0 {
		t.Error("erro de banco não foi registrado no log")
	}
}
