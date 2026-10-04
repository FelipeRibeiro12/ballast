package http

import (
	"net/http"

	"github.com/jackc/pgx/v5/pgxpool"
)

func NewRouter(pool *pgxpool.Pool, ident Identidade, registroAberto, cookieSecure bool) *http.ServeMux {
	mux := http.NewServeMux()
	// "GET" no padrão também cobre HEAD; os outros métodos recebem 405 do próprio mux.
	mux.HandleFunc("GET /healthz", HealthHandler(pool))
	// Fechado, a rota nem é registrada: qualquer método cai no 404 do mux, sem o
	// 405 e o cabeçalho Allow que denunciariam que o cadastro existe.
	if registroAberto {
		mux.HandleFunc("POST /auth/cadastro", cadastroHandler(ident))
	}
	mux.HandleFunc("POST /auth/login", loginHandler(ident, cookieSecure))
	mux.HandleFunc("GET /auth/eu", euHandler(ident))
	mux.HandleFunc("POST /auth/logout", logoutHandler(ident, cookieSecure))
	return mux
}
