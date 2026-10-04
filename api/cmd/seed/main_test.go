package main

import (
	"bytes"
	"context"
	"os"
	"strings"
	"testing"

	"github.com/jackc/pgx/v5/pgxpool"
)

const senhaSeed = "senha-do-seed-12345"

func ambienteSeed(t *testing.T, email, senha, nome string) (func(string) string, *pgxpool.Pool) {
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
	t.Cleanup(func() {
		if _, err := pool.Exec(context.Background(), "DELETE FROM usuario WHERE email = lower($1)", email); err != nil {
			t.Errorf("limpa usuário de teste: %v", err)
		}
	})
	env := map[string]string{"DATABASE_URL": url, "SEED_EMAIL": email, "SEED_SENHA": senha, "SEED_NOME": nome}
	return func(k string) string { return env[k] }, pool
}

func TestSeedCriaUsuarioEEhIdempotente(t *testing.T) {
	getenv, pool := ambienteSeed(t, "Seed-Teste@Exemplo.Test", senhaSeed, "Dono")
	var saida bytes.Buffer

	if err := run(context.Background(), getenv, &saida); err != nil {
		t.Fatalf("primeira execução: %v", err)
	}
	var email, nome, hash string
	err := pool.QueryRow(context.Background(), "SELECT email, nome, senha_hash FROM usuario WHERE email = 'seed-teste@exemplo.test'").Scan(&email, &nome, &hash)
	if err != nil {
		t.Fatalf("usuário não criado: %v", err)
	}
	if nome != "Dono" || !strings.HasPrefix(hash, "$argon2id$") {
		t.Errorf("nome=%q hash=%q", nome, hash)
	}
	if !strings.Contains(saida.String(), "criado") {
		t.Errorf("saída = %q, want dizer que criou", saida.String())
	}

	saida.Reset()
	if err := run(context.Background(), getenv, &saida); err != nil {
		t.Fatalf("segunda execução: %v", err)
	}
	if !strings.Contains(saida.String(), "já existe") {
		t.Errorf("saída = %q, want dizer que já existe", saida.String())
	}
	var n int
	err = pool.QueryRow(context.Background(), "SELECT count(*) FROM usuario WHERE email = 'seed-teste@exemplo.test'").Scan(&n)
	if err != nil || n != 1 {
		t.Errorf("%d usuários (err=%v), want 1", n, err)
	}
}

func TestSeedNaoImprimeSenhaNemEmail(t *testing.T) {
	getenv, _ := ambienteSeed(t, "seed-saida@exemplo.test", senhaSeed, "")
	var saida bytes.Buffer
	if err := run(context.Background(), getenv, &saida); err != nil {
		t.Fatal(err)
	}
	for _, proibido := range []string{senhaSeed, "seed-saida@exemplo.test", "argon2"} {
		if strings.Contains(saida.String(), proibido) {
			t.Errorf("saída contém %q", proibido)
		}
	}
}

func TestSeedExigeVariaveisESenhaNoTamanho(t *testing.T) {
	casos := map[string][3]string{
		"sem e-mail":      {"", senhaSeed, ""},
		"sem senha":       {"a@exemplo.test", "", ""},
		"senha curta":     {"a@exemplo.test", "curta", ""},
		"senha gigante":   {"a@exemplo.test", strings.Repeat("a", 129), ""},
		"e-mail inválido": {"sem-arroba", senhaSeed, ""},
	}
	for nome, c := range casos {
		t.Run(nome, func(t *testing.T) {
			getenv, _ := ambienteSeed(t, c[0], c[1], c[2])
			err := run(context.Background(), getenv, &bytes.Buffer{})
			if err == nil {
				t.Fatal("run aceitou entrada inválida")
			}
			if strings.Contains(err.Error(), c[1]) && c[1] != "" {
				t.Errorf("erro contém a senha: %v", err)
			}
		})
	}
}
