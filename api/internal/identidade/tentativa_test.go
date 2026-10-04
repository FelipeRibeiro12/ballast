package identidade

import (
	"context"
	"os"
	"sync"
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

// Falhas simultâneas na mesma chave não podem perder contagem: ler-e-gravar
// deixaria duas requisições gravarem o mesmo valor.
func TestRegistraFalhaSimultaneaSomaExatamente(t *testing.T) {
	pool := poolDeTeste(t)
	s := NovoServico(pool)
	email := "concorrencia-atomica@exemplo.test"
	t.Cleanup(func() { _, _ = pool.Exec(context.Background(), "DELETE FROM tentativa_login WHERE email = $1", email) })
	_, _ = pool.Exec(context.Background(), "DELETE FROM tentativa_login WHERE email = $1", email)

	const n = 40
	var wg sync.WaitGroup
	erros := make(chan error, n)
	for range n {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if _, err := s.registraFalha(context.Background(), email); err != nil {
				erros <- err
			}
		}()
	}
	wg.Wait()
	close(erros)
	for err := range erros {
		t.Fatalf("registraFalha: %v", err)
	}

	var falhas int
	if err := pool.QueryRow(context.Background(), "SELECT falhas FROM tentativa_login WHERE email = $1", email).Scan(&falhas); err != nil {
		t.Fatalf("lê falhas: %v", err)
	}
	if falhas != n {
		t.Errorf("falhas = %d, want %d", falhas, n)
	}
}
