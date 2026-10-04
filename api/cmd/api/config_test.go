package main

import "testing"

func TestLeCookieSecure(t *testing.T) {
	casos := []struct {
		nome    string
		valor   string
		appEnv  string
		want    bool
		wantErr bool
	}{
		{"ausente vale true", "", "", true, false},
		{"ausente vale true em development", "", "development", true, false},
		{"true em produção", "true", "production", true, false},
		{"true sem APP_ENV", "true", "", true, false},
		{"true em development", "true", "development", true, false},
		{"false em development", "false", "development", false, false},
		{"false sem APP_ENV recusa", "false", "", false, true},
		{"false em production recusa", "false", "production", false, true},
		{"false em staging recusa", "false", "staging", false, true},
		{"false com caixa diferente de development recusa", "false", "Development", false, true},
		{"valor inválido recusa", "talvez", "development", false, true},
		{"valor numérico recusa", "0", "development", false, true},
		{"caixa diferente recusa", "FALSE", "development", false, true},
	}

	for _, c := range casos {
		t.Run(c.nome, func(t *testing.T) {
			got, err := leCookieSecure(c.valor, c.appEnv)
			if (err != nil) != c.wantErr {
				t.Fatalf("leCookieSecure(%q, %q) erro = %v, wantErr %v", c.valor, c.appEnv, err, c.wantErr)
			}
			if got != c.want {
				t.Errorf("leCookieSecure(%q, %q) = %v, quero %v", c.valor, c.appEnv, got, c.want)
			}
		})
	}
}
