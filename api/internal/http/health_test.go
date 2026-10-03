package http

import (
	"bytes"
	"context"
	"encoding/json"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"

	"github.com/jackc/pgx/v5/pgxpool"
)

// No CI, teste pulado é falso verde: sem DATABASE_URL o teste tem que falhar.
func poolDeTeste(t *testing.T) *pgxpool.Pool {
	t.Helper()
	url := os.Getenv("DATABASE_URL")
	if url == "" {
		if os.Getenv("CI") != "" {
			t.Fatal("DATABASE_URL vazia no CI: teste de integração não pode ser pulado")
		}
		t.Skip("DATABASE_URL vazia: pulando teste de integração (rode make db-up e make migrate)")
	}
	pool, err := pgxpool.New(context.Background(), url)
	if err != nil {
		t.Fatalf("cria pool: %v", err)
	}
	t.Cleanup(pool.Close)
	return pool
}

func TestHealthLeDoBanco(t *testing.T) {
	pool := poolDeTeste(t)

	rec := httptest.NewRecorder()
	NewRouter(pool).ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/healthz", nil))

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want %d", rec.Code, http.StatusOK)
	}
	if ct := rec.Header().Get("Content-Type"); ct != "application/json" {
		t.Errorf("content-type = %q, want application/json", ct)
	}
	var body struct {
		Mensagem string `json:"mensagem"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatalf("corpo não é JSON: %v", err)
	}
	if body.Mensagem != "quebrado-de-proposito" {
		t.Errorf("mensagem = %q, want %q", body.Mensagem, "ok")
	}
}

func TestHealthBancoInacessivel(t *testing.T) {
	const senha = "senha-secreta-123"
	// Porta 1 recusa conexão; o pool é lazy, então o erro só aparece na query.
	pool, err := pgxpool.New(context.Background(),
		"postgres://usuario:"+senha+"@127.0.0.1:1/ballast?sslmode=disable&connect_timeout=1")
	if err != nil {
		t.Fatalf("cria pool: %v", err)
	}
	defer pool.Close()

	var logBuf bytes.Buffer
	anterior := slog.Default()
	slog.SetDefault(slog.New(slog.NewTextHandler(&logBuf, nil)))
	defer slog.SetDefault(anterior)

	rec := httptest.NewRecorder()
	NewRouter(pool).ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/healthz", nil))

	if rec.Code != http.StatusServiceUnavailable {
		t.Fatalf("status = %d, want %d", rec.Code, http.StatusServiceUnavailable)
	}
	corpo := rec.Body.String()
	for _, proibido := range []string{senha, "127.0.0.1", "usuario", "dial", "connect", "postgres"} {
		if strings.Contains(corpo, proibido) {
			t.Errorf("corpo vaza %q: %s", proibido, corpo)
		}
	}
	if logBuf.Len() == 0 {
		t.Error("falha de banco não foi registrada no log do servidor")
	}
	if strings.Contains(logBuf.String(), senha) {
		t.Error("log contém a senha da DSN")
	}
}

func TestHealthRejectsOtherMethods(t *testing.T) {
	req := httptest.NewRequest(http.MethodPost, "/healthz", nil)
	rec := httptest.NewRecorder()

	// O mux responde 405 antes de chegar no handler, então o pool não é usado.
	NewRouter(nil).ServeHTTP(rec, req)

	if rec.Code != http.StatusMethodNotAllowed {
		t.Errorf("status = %d, want %d", rec.Code, http.StatusMethodNotAllowed)
	}
}
