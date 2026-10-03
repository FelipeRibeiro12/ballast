package http

import (
	"log/slog"
	"net/http"
)

func HealthHandler(w http.ResponseWriter, r *http.Request) {
	if _, err := w.Write([]byte("ok")); err != nil {
		slog.Error("escrita do healthz falhou", "err", err)
	}
}
