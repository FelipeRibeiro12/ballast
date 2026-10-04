package identidade

import (
	"strings"
	"testing"
)

func TestHashSenhaUsaFormatoPHCComOsParametrosDefinidos(t *testing.T) {
	hash, err := HashSenha("uma senha bem longa")
	if err != nil {
		t.Fatalf("HashSenha: %v", err)
	}
	if !strings.HasPrefix(hash, "$argon2id$v=19$m=65536,t=3,p=2$") {
		t.Errorf("hash = %q, prefixo PHC inesperado", hash)
	}
	if partes := strings.Split(hash, "$"); len(partes) != 6 {
		t.Errorf("hash tem %d partes, want 6", len(partes))
	}
	if strings.Contains(hash, "uma senha") {
		t.Error("hash contém a senha em claro")
	}
}

func TestHashSenhaGeraSaltDiferenteACadaChamada(t *testing.T) {
	a, _ := HashSenha("mesma senha longa")
	b, _ := HashSenha("mesma senha longa")
	if a == b {
		t.Error("dois hashes da mesma senha são idênticos: salt não está variando")
	}
}

func TestVerificaSenha(t *testing.T) {
	hash, err := HashSenha("senha correta 123")
	if err != nil {
		t.Fatalf("HashSenha: %v", err)
	}
	if !VerificaSenha("senha correta 123", hash) {
		t.Error("senha correta rejeitada")
	}
	if VerificaSenha("senha errada 1234", hash) {
		t.Error("senha errada aceita")
	}
}

func TestVerificaSenhaRejeitaHashMalformado(t *testing.T) {
	for _, hash := range []string{
		"",
		"texto qualquer",
		"$argon2id$v=19$m=65536,t=3,p=2$@@@$@@@",
		"$argon2i$v=19$m=65536,t=3,p=2$AAAA$AAAA",
		"$argon2id$v=19$m=abc,t=3,p=2$AAAA$AAAA",
		"$argon2id$v=19$m=65536,t=3,p=2$AAAA",
	} {
		if VerificaSenha("qualquer coisa", hash) {
			t.Errorf("hash malformado %q aceito", hash)
		}
	}
}

func TestVerificaSenhaFicticiaNuncaAceita(t *testing.T) {
	if VerificaSenhaFicticia("qualquer coisa") {
		t.Error("verificador fictício aceitou uma senha")
	}
}
