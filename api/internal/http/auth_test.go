package http

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"log/slog"
	"maps"
	"net/http"
	"net/http/httptest"
	"slices"
	"strings"
	"testing"
	"time"

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

// Cria o usuário pela API, que é o caminho que o app usa.
func (a *ambiente) cadastra(t *testing.T, email string) usuarioResposta {
	t.Helper()
	_, rec := postJSON(a.h, "/auth/cadastro", map[string]string{"email": email, "senha": senhaDeTeste, "nome": "Fulano"})
	if rec.Code != http.StatusCreated {
		t.Fatalf("cadastro de apoio: status = %d (corpo: %s)", rec.Code, rec.Body)
	}
	var u usuarioResposta
	if err := json.Unmarshal(rec.Body.Bytes(), &u); err != nil {
		t.Fatal(err)
	}
	return u
}

func cookieDe(rec *httptest.ResponseRecorder, nome string) *http.Cookie {
	for _, c := range rec.Result().Cookies() {
		if c.Name == nome {
			return c
		}
	}
	return nil
}

func TestLoginAbreSessao(t *testing.T) {
	a := novoAmbiente(t, true)
	email := emailUnico(t, a.pool)
	criado := a.cadastra(t, email)
	logBuf := capturaLog(t)

	_, rec := postJSON(a.h, "/auth/login", map[string]string{"email": email, "senha": senhaDeTeste})

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200 (corpo: %s)", rec.Code, rec.Body)
	}
	var u usuarioResposta
	_ = json.Unmarshal(rec.Body.Bytes(), &u)
	if u != (usuarioResposta{ID: criado.ID, Email: email, Nome: u.Nome}) || u.Nome == nil || *u.Nome != "Fulano" {
		t.Errorf("resposta = %+v, want usuário %+v", u, criado)
	}
	c := cookieDe(rec, "ballast_sessao")
	if c == nil || c.Value == "" {
		t.Fatalf("sem cookie de sessão: %v", rec.Header()["Set-Cookie"])
	}

	if strings.Contains(rec.Body.String(), senhaDeTeste) || strings.Contains(rec.Body.String(), c.Value) {
		t.Error("corpo vaza senha ou token")
	}
	for _, proibido := range []string{senhaDeTeste, c.Value, "argon2", email} {
		if strings.Contains(logBuf.String(), proibido) {
			t.Errorf("log contém %q", proibido)
		}
	}
	if !strings.Contains(logBuf.String(), criado.ID) {
		t.Errorf("login bem-sucedido sem usuario_id no log: %s", logBuf)
	}
}

func TestLoginGravaSoOHashDoToken(t *testing.T) {
	a := novoAmbiente(t, true)
	email := emailUnico(t, a.pool)
	u := a.cadastra(t, email)

	_, rec := postJSON(a.h, "/auth/login", map[string]string{"email": email, "senha": senhaDeTeste})
	c := cookieDe(rec, "ballast_sessao")
	if c == nil {
		t.Fatalf("login sem cookie (status %d)", rec.Code)
	}
	token := c.Value

	bruto, err := base64.RawURLEncoding.DecodeString(token)
	if err != nil || len(bruto) != 32 {
		t.Fatalf("token deve ser base64url de 32 bytes (len=%d, err=%v)", len(bruto), err)
	}
	var hashNoBanco []byte
	var duracao string
	err = a.pool.QueryRow(context.Background(),
		"SELECT token_hash, (expira_em - criada_em)::text FROM sessao WHERE usuario_id = $1", u.ID).Scan(&hashNoBanco, &duracao)
	if err != nil {
		t.Fatalf("sessão não gravada: %v", err)
	}
	esperado := sha256.Sum256([]byte(token))
	if !bytes.Equal(hashNoBanco, esperado[:]) {
		t.Error("token_hash não é o SHA-256 do token do cookie")
	}
	if bytes.Equal(hashNoBanco, bruto) || bytes.Contains(hashNoBanco, []byte(token)) {
		t.Error("o token em claro foi gravado no banco")
	}
	// criada_em usa o relógio do banco e expira_em o da aplicação: tolera folga.
	if !strings.HasPrefix(duracao, "6 days 23:59") && !strings.HasPrefix(duracao, "7 days") {
		t.Errorf("duração da sessão = %q, want 7 dias", duracao)
	}
}

func TestLoginComEmailInexistenteESenhaErradaSaoIdenticos(t *testing.T) {
	a := novoAmbiente(t, true)
	email := emailUnico(t, a.pool)
	a.cadastra(t, email)

	_, senhaErrada := postJSON(a.h, "/auth/login", map[string]string{"email": email, "senha": "senha-errada-12345"})
	_, inexistente := postJSON(a.h, "/auth/login", map[string]string{"email": emailUnico(t, nil), "senha": "senha-errada-12345"})

	if senhaErrada.Code != http.StatusUnauthorized || inexistente.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d e %d, want 401 nos dois", senhaErrada.Code, inexistente.Code)
	}
	if senhaErrada.Body.String() != inexistente.Body.String() {
		t.Errorf("corpos diferem: %q x %q", senhaErrada.Body, inexistente.Body)
	}
	if want := `{"erro":"credenciais inválidas"}` + "\n"; senhaErrada.Body.String() != want {
		t.Errorf("corpo = %q, want %q", senhaErrada.Body, want)
	}
	for _, rec := range []*httptest.ResponseRecorder{senhaErrada, inexistente} {
		if len(rec.Header().Values("Set-Cookie")) != 0 {
			t.Error("falha de login definiu cookie")
		}
	}
	if !maps.EqualFunc(senhaErrada.Header(), inexistente.Header(), slices.Equal) {
		t.Errorf("cabeçalhos diferem: %v x %v", senhaErrada.Header(), inexistente.Header())
	}
}

// O e-mail inexistente precisa gastar o argon2 também; sem isso o tempo de
// resposta entrega quais e-mails têm conta. Compara com folga larga (o argon2
// custa ordens de grandeza mais que a consulta) para não ficar instável.
func TestLoginComEmailInexistenteGastaOTempoDoArgon2(t *testing.T) {
	a := novoAmbiente(t, true)
	email := emailUnico(t, a.pool)
	a.cadastra(t, email)
	postJSON(a.h, "/auth/login", map[string]string{"email": emailUnico(t, nil), "senha": "senha-errada-12345"}) // aquece o hash fictício

	medir := func(email string) time.Duration {
		inicio := time.Now()
		postJSON(a.h, "/auth/login", map[string]string{"email": email, "senha": "senha-errada-12345"})
		return time.Since(inicio)
	}
	existente, inexistente := medir(email), medir(emailUnico(t, nil))
	if inexistente < existente/3 {
		t.Errorf("e-mail inexistente respondeu em %v e o existente em %v: o argon2 fictício não está rodando", inexistente, existente)
	}
}

func TestLoginNormalizaACaixaDoEmail(t *testing.T) {
	a := novoAmbiente(t, true)
	email := emailUnico(t, a.pool)
	a.cadastra(t, email)

	_, rec := postJSON(a.h, "/auth/login", map[string]string{"email": " " + strings.ToUpper(email) + " ", "senha": senhaDeTeste})
	if rec.Code != http.StatusOK {
		t.Errorf("status = %d, want 200", rec.Code)
	}
}

func TestLoginFalhaRegistraEmailMascarado(t *testing.T) {
	a := novoAmbiente(t, true)
	email := emailUnico(t, a.pool)
	a.cadastra(t, email)
	logBuf := capturaLog(t)

	postJSON(a.h, "/auth/login", map[string]string{"email": email, "senha": "senha-errada-12345"})

	log := logBuf.String()
	if !strings.Contains(log, "t***@exemplo.test") || !strings.Contains(log, "192.0.2.1") {
		t.Errorf("log da falha sem e-mail mascarado e IP: %s", log)
	}
	for _, proibido := range []string{email, "senha-errada-12345", senhaDeTeste} {
		if strings.Contains(log, proibido) {
			t.Errorf("log contém %q", proibido)
		}
	}
}

func TestLoginValidaEntrada(t *testing.T) {
	a := novoAmbiente(t, true)
	casos := map[string]string{
		"campo desconhecido":   `{"email":"a@exemplo.test","senha":"x","lembrar":true}`,
		"senha ausente":        `{"email":"a@exemplo.test"}`,
		"senha vazia":          `{"email":"a@exemplo.test","senha":""}`,
		"senha de 129":         `{"email":"a@exemplo.test","senha":"` + strings.Repeat("a", 129) + `"}`,
		"e-mail inválido":      `{"email":"nao-e-email","senha":"x"}`,
		"json quebrado":        `{`,
		"corpo acima de 8 KiB": `{"email":"a@exemplo.test","senha":"` + strings.Repeat("a", 9000) + `"}`,
	}
	for nome, corpo := range casos {
		t.Run(nome, func(t *testing.T) {
			_, rec := postJSON(a.h, "/auth/login", corpo)
			if rec.Code != http.StatusBadRequest {
				t.Errorf("status = %d, want 400 (corpo: %s)", rec.Code, rec.Body)
			}
		})
	}
}

func TestLoginAceitaSenhaCurtaEDevolve401(t *testing.T) {
	// A política de tamanho vale só no cadastro; no login, senha curta é senha errada.
	a := novoAmbiente(t, true)
	_, rec := postJSON(a.h, "/auth/login", map[string]string{"email": emailUnico(t, nil), "senha": "curta"})
	if rec.Code != http.StatusUnauthorized {
		t.Errorf("status = %d, want 401", rec.Code)
	}
}

func TestLoginRejeitaContentTypeQueNaoSejaJSON(t *testing.T) {
	a := novoAmbiente(t, true)
	req := httptest.NewRequest(http.MethodPost, "/auth/login", strings.NewReader("email=a"))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	rec := httptest.NewRecorder()
	a.h.ServeHTTP(rec, req)
	if rec.Code != http.StatusUnsupportedMediaType {
		t.Errorf("status = %d, want 415", rec.Code)
	}
}

func TestLoginComBancoIndisponivelNaoVazaDetalhe(t *testing.T) {
	pool := poolIndisponivel(t)
	h := NewRouter(pool, identidade.NovoServico(pool), true)
	logBuf := capturaLog(t)

	_, rec := postJSON(h, "/auth/login", map[string]string{"email": "a@exemplo.test", "senha": senhaDeTeste})

	if rec.Code != http.StatusInternalServerError {
		t.Fatalf("status = %d, want 500", rec.Code)
	}
	for _, proibido := range []string{"127.0.0.1", "dial", "connect", "postgres"} {
		if strings.Contains(rec.Body.String(), proibido) {
			t.Errorf("corpo vaza %q: %s", proibido, rec.Body)
		}
	}
	if logBuf.Len() == 0 || strings.Contains(logBuf.String(), senhaDeTeste) {
		t.Errorf("log vazio ou com a senha: %s", logBuf)
	}
}

func getComCookie(h http.Handler, path string, c *http.Cookie) (*http.Request, *httptest.ResponseRecorder) {
	req := httptest.NewRequest(http.MethodGet, path, nil)
	if c != nil {
		req.AddCookie(c)
	}
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	return req, rec
}

func postComCookie(h http.Handler, path string, c *http.Cookie) (*http.Request, *httptest.ResponseRecorder) {
	req := httptest.NewRequest(http.MethodPost, path, nil)
	if c != nil {
		req.AddCookie(c)
	}
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	return req, rec
}

// Cadastra, loga e devolve o cookie de sessão.
func (a *ambiente) sessao(t *testing.T) (usuarioResposta, *http.Cookie) {
	t.Helper()
	email := emailUnico(t, a.pool)
	u := a.cadastra(t, email)
	_, rec := postJSON(a.h, "/auth/login", map[string]string{"email": email, "senha": senhaDeTeste})
	c := cookieDe(rec, "ballast_sessao")
	if c == nil {
		t.Fatalf("login de apoio sem cookie (status %d)", rec.Code)
	}
	return u, &http.Cookie{Name: c.Name, Value: c.Value}
}

func TestEuDevolveOUsuarioDaSessao(t *testing.T) {
	a := novoAmbiente(t, true)
	u, c := a.sessao(t)

	_, rec := getComCookie(a.h, "/auth/eu", c)

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200 (corpo: %s)", rec.Code, rec.Body)
	}
	var got usuarioResposta
	_ = json.Unmarshal(rec.Body.Bytes(), &got)
	if got.ID != u.ID || got.Email != u.Email || got.Nome == nil || *got.Nome != "Fulano" {
		t.Errorf("resposta = %+v, want usuário %+v", got, u)
	}
	if strings.Contains(rec.Body.String(), "senha") {
		t.Errorf("resposta menciona senha: %s", rec.Body)
	}
}

func TestEuIgnoraIdentificadorVindoDoCliente(t *testing.T) {
	a := novoAmbiente(t, true)
	usuarioA, cookieA := a.sessao(t)
	usuarioB, _ := a.sessao(t)

	req := httptest.NewRequest(http.MethodGet, "/auth/eu?id="+usuarioB.ID+"&usuario_id="+usuarioB.ID, nil)
	req.AddCookie(cookieA)
	req.Header.Set("X-Usuario-Id", usuarioB.ID)
	rec := httptest.NewRecorder()
	a.h.ServeHTTP(rec, req)

	var got usuarioResposta
	_ = json.Unmarshal(rec.Body.Bytes(), &got)
	if got.ID != usuarioA.ID {
		t.Errorf("id = %s, want o do dono da sessão (%s)", got.ID, usuarioA.ID)
	}
}

func TestEuDevolve401IgualParaTodaSessaoInvalida(t *testing.T) {
	a := novoAmbiente(t, true)
	ctx := context.Background()

	_, expirada := a.sessao(t)
	if _, err := a.pool.Exec(ctx, "UPDATE sessao SET expira_em = now() - interval '1 second' WHERE token_hash = $1", hashParaTeste(expirada.Value)); err != nil {
		t.Fatal(err)
	}
	_, revogada := a.sessao(t)
	if _, err := a.pool.Exec(ctx, "UPDATE sessao SET revogada_em = now() WHERE token_hash = $1", hashParaTeste(revogada.Value)); err != nil {
		t.Fatal(err)
	}

	casos := map[string]*http.Cookie{
		"sem cookie":         nil,
		"token desconhecido": {Name: "ballast_sessao", Value: base64.RawURLEncoding.EncodeToString(make([]byte, 32))},
		"token lixo":         {Name: "ballast_sessao", Value: "%%%"},
		"cookie vazio":       {Name: "ballast_sessao", Value: ""},
		"sessão expirada":    expirada,
		"sessão revogada":    revogada,
	}
	var referencia string
	for nome, c := range casos {
		t.Run(nome, func(t *testing.T) {
			_, rec := getComCookie(a.h, "/auth/eu", c)
			if rec.Code != http.StatusUnauthorized {
				t.Fatalf("status = %d, want 401", rec.Code)
			}
			if referencia == "" {
				referencia = rec.Body.String()
			}
			if rec.Body.String() != referencia {
				t.Errorf("corpo = %q, diferente dos outros 401 (%q)", rec.Body, referencia)
			}
		})
	}
}

func hashParaTeste(token string) []byte {
	h := sha256.Sum256([]byte(token))
	return h[:]
}

func TestLogoutRevogaNoBancoEExpiraOCookie(t *testing.T) {
	a := novoAmbiente(t, true)
	u, c := a.sessao(t)
	logBuf := capturaLog(t)

	_, rec := postComCookie(a.h, "/auth/logout", c)

	if rec.Code != http.StatusNoContent {
		t.Fatalf("status = %d, want 204", rec.Code)
	}
	if rec.Body.Len() != 0 {
		t.Errorf("204 com corpo: %q", rec.Body)
	}
	exp := cookieDe(rec, "ballast_sessao")
	if exp == nil || exp.MaxAge >= 0 || exp.Value != "" || !exp.HttpOnly || !exp.Secure {
		t.Errorf("cookie de expiração = %+v, want Max-Age=0, vazio, HttpOnly e Secure", exp)
	}
	if linha := rec.Header().Get("Set-Cookie"); !strings.Contains(linha, "Max-Age=0") {
		t.Errorf("Set-Cookie = %q, want Max-Age=0", linha)
	}

	var revogada *time.Time
	err := a.pool.QueryRow(context.Background(), "SELECT revogada_em FROM sessao WHERE usuario_id = $1", u.ID).Scan(&revogada)
	if err != nil {
		t.Fatal(err)
	}
	if revogada == nil {
		t.Error("revogada_em continua nulo: o logout só apagou o cookie")
	}

	// O mesmo token, reapresentado depois, não vale mais.
	if _, rec := getComCookie(a.h, "/auth/eu", c); rec.Code != http.StatusUnauthorized {
		t.Errorf("eu após logout: status = %d, want 401", rec.Code)
	}
	if strings.Contains(logBuf.String(), c.Value) {
		t.Error("log contém o token")
	}
	if !strings.Contains(logBuf.String(), "logout") {
		t.Errorf("logout não registrado no log: %s", logBuf)
	}
}

func TestLogoutEhIdempotenteESemSessaoTambemDa204(t *testing.T) {
	a := novoAmbiente(t, true)
	u, c := a.sessao(t)

	postComCookie(a.h, "/auth/logout", c)
	var primeira time.Time
	if err := a.pool.QueryRow(context.Background(), "SELECT revogada_em FROM sessao WHERE usuario_id = $1", u.ID).Scan(&primeira); err != nil {
		t.Fatal(err)
	}

	casos := map[string]*http.Cookie{
		"de novo com o mesmo token": c,
		"sem cookie":                nil,
		"token desconhecido":        {Name: "ballast_sessao", Value: "nao-existe"},
	}
	for nome, cookie := range casos {
		_, rec := postComCookie(a.h, "/auth/logout", cookie)
		if rec.Code != http.StatusNoContent {
			t.Errorf("%s: status = %d, want 204", nome, rec.Code)
		}
	}
	var depois time.Time
	if err := a.pool.QueryRow(context.Background(), "SELECT revogada_em FROM sessao WHERE usuario_id = $1", u.ID).Scan(&depois); err != nil {
		t.Fatal(err)
	}
	if !depois.Equal(primeira) {
		t.Error("segundo logout sobrescreveu revogada_em")
	}
}

func TestLogoutNaoRevogaSessaoDeOutroUsuario(t *testing.T) {
	a := novoAmbiente(t, true)
	_, cookieA := a.sessao(t)
	_, cookieB := a.sessao(t)

	postComCookie(a.h, "/auth/logout", cookieA)

	if _, rec := getComCookie(a.h, "/auth/eu", cookieB); rec.Code != http.StatusOK {
		t.Errorf("logout de A derrubou a sessão de B: status = %d", rec.Code)
	}
}

func TestEuELogoutComBancoIndisponivelDao500SemVazar(t *testing.T) {
	pool := poolIndisponivel(t)
	h := NewRouter(pool, identidade.NovoServico(pool), true)
	c := &http.Cookie{Name: "ballast_sessao", Value: "qualquer"}
	logBuf := capturaLog(t)

	_, eu := getComCookie(h, "/auth/eu", c)
	_, logout := postComCookie(h, "/auth/logout", c)

	for nome, rec := range map[string]*httptest.ResponseRecorder{"eu": eu, "logout": logout} {
		if rec.Code != http.StatusInternalServerError {
			t.Errorf("%s: status = %d, want 500", nome, rec.Code)
		}
		for _, proibido := range []string{"127.0.0.1", "dial", "connect", "postgres"} {
			if strings.Contains(rec.Body.String(), proibido) {
				t.Errorf("%s: corpo vaza %q", nome, proibido)
			}
		}
	}
	if strings.Contains(logBuf.String(), "qualquer") {
		t.Error("log contém o token")
	}
}
