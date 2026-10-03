package main

import "testing"

func TestListenAddr(t *testing.T) {
	casos := []struct {
		nome string
		host string
		port string
		want string
	}{
		{"host vazio usa loopback", "", "8080", "127.0.0.1:8080"},
		{"host explícito", "0.0.0.0", "8080", "0.0.0.0:8080"},
		{"ipv6 ganha colchetes", "::1", "8080", "[::1]:8080"},
		{"porta custom", "127.0.0.1", "18080", "127.0.0.1:18080"},
		{"host vazio com porta custom", "", "18080", "127.0.0.1:18080"},
	}

	for _, c := range casos {
		t.Run(c.nome, func(t *testing.T) {
			if got := listenAddr(c.host, c.port); got != c.want {
				t.Errorf("listenAddr(%q, %q) = %q, quero %q", c.host, c.port, got, c.want)
			}
		})
	}
}
