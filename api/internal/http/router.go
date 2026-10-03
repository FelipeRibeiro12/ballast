package http

import (
	"net/http"

	"github.com/jackc/pgx/v5/pgxpool"
)

func NewRouter(pool *pgxpool.Pool) *http.ServeMux {
	mux := http.NewServeMux()
	// "GET" no padrão também cobre HEAD; os outros métodos recebem 405 do próprio mux.
	mux.HandleFunc("GET /healthz", HealthHandler(pool))
	return mux
}
