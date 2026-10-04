package main

import (
	"net/http"
	"testing"
)

// Timeout zero significa "sem limite": conexão lenta ou parada segura uma
// goroutine para sempre.
func TestNovoServidorDefineTodosOsTimeouts(t *testing.T) {
	srv := novoServidor("127.0.0.1:0", http.NewServeMux())

	if srv.ReadHeaderTimeout <= 0 {
		t.Error("ReadHeaderTimeout não definido")
	}
	if srv.ReadTimeout <= 0 {
		t.Error("ReadTimeout não definido")
	}
	if srv.WriteTimeout <= 0 {
		t.Error("WriteTimeout não definido")
	}
	if srv.IdleTimeout <= 0 {
		t.Error("IdleTimeout não definido")
	}
	if srv.ReadTimeout < srv.ReadHeaderTimeout {
		t.Error("ReadTimeout menor que ReadHeaderTimeout")
	}
}
