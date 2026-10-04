package http

import (
	"context"
	"encoding/json"
	"net/http"
	"strings"
	"testing"
)

// Os caracteres ficam no meio do texto: as bordas seriam aparadas por TrimSpace
// e não provariam a validação.
var caracteresDeControle = map[string]string{
	"NUL":                 "\u0000",
	"BEL":                 "\u0007",
	"quebra de linha":     "\n",
	"DEL":                 "\u007f",
	"NEL":                 "\u0085",
	"separador de linha":  " ",
	"separador parágrafo": " ",
}

func exigeErro400(t *testing.T, status int, corpo string, entrada string) {
	t.Helper()
	if status != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400 (corpo: %s)", status, corpo)
	}
	var erro map[string]string
	if err := json.Unmarshal([]byte(corpo), &erro); err != nil || erro["erro"] == "" || len(erro) != 1 {
		t.Errorf("corpo = %s, want {\"erro\": \"...\"}", corpo)
	}
	if strings.Contains(corpo, entrada) {
		t.Errorf("corpo ecoa a entrada: %s", corpo)
	}
}

func TestCadastroRejeitaControleNoEmail(t *testing.T) {
	a := novoAmbiente(t, true)
	for nome, c := range caracteresDeControle {
		t.Run(nome, func(t *testing.T) {
			email := "ab" + c + "cd@exemplo.test"
			_, rec := postJSON(a.h, "/auth/cadastro", map[string]string{"email": email, "senha": senhaDeTeste})
			exigeErro400(t, rec.Code, rec.Body.String(), email)
		})
	}
}

func TestCadastroRejeitaControleNoNome(t *testing.T) {
	a := novoAmbiente(t, true)
	for nome, c := range caracteresDeControle {
		t.Run(nome, func(t *testing.T) {
			email := emailUnico(t, a.pool)
			nomeInvalido := "Fu" + c + "lano"
			_, rec := postJSON(a.h, "/auth/cadastro", map[string]string{"email": email, "senha": senhaDeTeste, "nome": nomeInvalido})
			exigeErro400(t, rec.Code, rec.Body.String(), nomeInvalido)
		})
	}
}

func TestLoginRejeitaControleNoEmailSemGravarTentativa(t *testing.T) {
	a := novoAmbiente(t, true)
	for nome, c := range caracteresDeControle {
		t.Run(nome, func(t *testing.T) {
			// O marcador único permite achar a linha no banco sem depender de outros testes.
			local, _, _ := strings.Cut(emailUnico(t, nil), "@")
			marcador := "marcador" + local
			email := marcador + c + "x@exemplo.test"
			_, rec := postJSON(a.h, "/auth/login", map[string]string{"email": email, "senha": senhaDeTeste})
			exigeErro400(t, rec.Code, rec.Body.String(), email)

			var linhas int
			err := a.pool.QueryRow(context.Background(),
				"SELECT count(*) FROM tentativa_login WHERE position($1 in email) > 0", marcador).Scan(&linhas)
			if err != nil {
				t.Fatalf("conta tentativas: %v", err)
			}
			if linhas != 0 {
				t.Errorf("tentativa_login tem %d linha(s) para e-mail inválido, want 0", linhas)
			}
		})
	}
}

// A senha não vai ao banco, só ao argon2: qualquer caractere, inclusive NUL, vale.
func TestSenhaComControleNaoViraErro(t *testing.T) {
	a := novoAmbiente(t, true)
	email := emailUnico(t, a.pool)
	senha := "senha\u0000com\u0007controle\n123"

	_, rec := postJSON(a.h, "/auth/cadastro", map[string]string{"email": email, "senha": senha})
	if rec.Code != http.StatusCreated {
		t.Fatalf("cadastro: status = %d, want 201 (corpo: %s)", rec.Code, rec.Body)
	}
	_, rec = postJSON(a.h, "/auth/login", map[string]string{"email": email, "senha": senha})
	if rec.Code != http.StatusOK {
		t.Fatalf("login: status = %d, want 200 (corpo: %s)", rec.Code, rec.Body)
	}
}
