package http

import "net/http"

func NewRouter() *http.ServeMux {
	mux := http.NewServeMux()
	// "GET" no padrão também cobre HEAD; os outros métodos recebem 405 do próprio mux.
	mux.HandleFunc("GET /healthz", HealthHandler)
	return mux
}
