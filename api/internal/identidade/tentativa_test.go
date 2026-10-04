package identidade

import (
	"context"
	"os"
	"sync"
	"sync/atomic"
	"testing"
	"time"

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

// Reservas simultâneas na mesma chave: ler-e-gravar concederia todas. O UPSERT
// condicional concede só as que cabem antes do atraso e conta cada uma.
func TestReservaTentativaSimultaneaConcedeSoOLimite(t *testing.T) {
	pool := poolDeTeste(t)
	s := NovoServico(pool, ComRelogio(func() time.Time { return time.Date(2026, 3, 10, 12, 0, 0, 0, time.UTC) }))
	email := "concorrencia-atomica@exemplo.test"
	t.Cleanup(func() { _, _ = pool.Exec(context.Background(), "DELETE FROM tentativa_login WHERE email = $1", email) })
	_, _ = pool.Exec(context.Background(), "DELETE FROM tentativa_login WHERE email = $1", email)

	const n = 40
	var wg sync.WaitGroup
	var concedidas atomic.Int32
	erros := make(chan error, n)
	for range n {
		wg.Add(1)
		go func() {
			defer wg.Done()
			ok, _, err := s.reservaTentativa(context.Background(), email)
			if err != nil {
				erros <- err
			}
			if ok {
				concedidas.Add(1)
			}
		}()
	}
	wg.Wait()
	close(erros)
	for err := range erros {
		t.Fatalf("reservaTentativa: %v", err)
	}

	var falhas int
	if err := pool.QueryRow(context.Background(), "SELECT falhas FROM tentativa_login WHERE email = $1", email).Scan(&falhas); err != nil {
		t.Fatalf("lê falhas: %v", err)
	}
	if concedidas.Load() != falhasSemAtraso+1 || falhas != falhasSemAtraso+1 {
		t.Errorf("concedidas = %d, falhas = %d, want %d nas duas", concedidas.Load(), falhas, falhasSemAtraso+1)
	}
}
