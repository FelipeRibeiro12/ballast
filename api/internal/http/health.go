package http

import (
	"context"
	"encoding/json"
	"log/slog"
	"net/http"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

// Health check não pode ficar pendurado: um banco lento vira 503 em vez de
// segurar a conexão do orquestrador.
const healthQueryTimeout = 2 * time.Second

func HealthHandler(pool *pgxpool.Pool) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		ctx, cancel := context.WithTimeout(r.Context(), healthQueryTimeout)
		defer cancel()

		var mensagem string
		err := pool.QueryRow(ctx, "SELECT mensagem FROM health_check WHERE id = 1").Scan(&mensagem)
		if err != nil {
			// O erro do driver pode carregar host e usuário da DSN: só vai para o log.
			slog.Error("health check: consulta ao banco falhou", "err", err)
			escreveJSON(w, http.StatusServiceUnavailable, map[string]string{"erro": "indisponível"})
			return
		}
		escreveJSON(w, http.StatusOK, map[string]string{"mensagem": mensagem})
	}
}

func escreveJSON(w http.ResponseWriter, status int, corpo any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	if err := json.NewEncoder(w).Encode(corpo); err != nil {
		slog.Error("escrita da resposta falhou", "err", err)
	}
}
