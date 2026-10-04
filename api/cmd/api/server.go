package main

import (
	"net/http"
	"time"
)

func novoServidor(addr string, handler http.Handler) *http.Server {
	return &http.Server{
		Addr:    addr,
		Handler: handler,
		// Sem ReadHeaderTimeout o servidor fica exposto a Slowloris.
		ReadHeaderTimeout: 5 * time.Second,
		// Os corpos têm no máximo 8 KiB, então 10 s para lê-los inteiros já é folga.
		ReadTimeout: 10 * time.Second,
		// Cobre o argon2 (centenas de ms) e as consultas com folga; sem limite, um
		// cliente que não lê a resposta prende a conexão.
		WriteTimeout: 15 * time.Second,
		// Mantém conexões ociosas por pouco tempo: é app pessoal, sem pico de reuso.
		IdleTimeout: 60 * time.Second,
	}
}
