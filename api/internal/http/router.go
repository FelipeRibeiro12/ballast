package http

import (
	"net/http"

	"github.com/jackc/pgx/v5/pgxpool"
)

func NewRouter(pool *pgxpool.Pool, ident Identidade, registroAberto bool) *http.ServeMux {
	mux := http.NewServeMux()
	// "GET" no padrão também cobre HEAD; os outros métodos recebem 405 do próprio mux.
	mux.HandleFunc("GET /healthz", HealthHandler(pool))
	mux.HandleFunc("POST /auth/cadastro", cadastroHandler(ident, registroAberto))
	mux.HandleFunc("POST /auth/login", loginHandler(ident))
	return mux
}
