package identidade

import (
	"crypto/rand"
	"crypto/subtle"
	"encoding/base64"
	"fmt"
	"strings"
	"sync"

	"golang.org/x/crypto/argon2"
)

// Parâmetros do argon2id. 64 MiB e t=3 deixam cada tentativa offline cara em
// GPU sem estourar a memória de uma instância pequena; p=2 aproveita 2 núcleos.
// O hash guarda os parâmetros em que foi gerado, então subir estes valores no
// futuro não invalida as senhas existentes.
const (
	argonMemoriaKiB = 64 * 1024
	argonIteracoes  = 3
	argonParalelo   = 2
	argonSaltBytes  = 16
	argonSaidaBytes = 32
)

func HashSenha(senha string) (string, error) {
	salt := make([]byte, argonSaltBytes)
	if _, err := rand.Read(salt); err != nil {
		return "", err
	}
	chave := argon2.IDKey([]byte(senha), salt, argonIteracoes, argonMemoriaKiB, argonParalelo, argonSaidaBytes)
	return fmt.Sprintf("$argon2id$v=%d$m=%d,t=%d,p=%d$%s$%s",
		argon2.Version, argonMemoriaKiB, argonIteracoes, argonParalelo,
		base64.RawStdEncoding.EncodeToString(salt),
		base64.RawStdEncoding.EncodeToString(chave)), nil
}

// VerificaSenha devolve false para qualquer hash que não consiga ler.
func VerificaSenha(senha, hash string) bool {
	partes := strings.Split(hash, "$")
	if len(partes) != 6 || partes[0] != "" || partes[1] != "argon2id" {
		return false
	}
	var versao int
	if _, err := fmt.Sscanf(partes[2], "v=%d", &versao); err != nil || versao != argon2.Version {
		return false
	}
	var memoria, iteracoes uint32
	var paralelo uint8
	if _, err := fmt.Sscanf(partes[3], "m=%d,t=%d,p=%d", &memoria, &iteracoes, &paralelo); err != nil {
		return false
	}
	salt, err := base64.RawStdEncoding.DecodeString(partes[4])
	if err != nil {
		return false
	}
	esperado, err := base64.RawStdEncoding.DecodeString(partes[5])
	if err != nil || len(esperado) == 0 {
		return false
	}
	obtido := argon2.IDKey([]byte(senha), salt, iteracoes, memoria, paralelo, uint32(len(esperado)))
	return subtle.ConstantTimeCompare(obtido, esperado) == 1
}

// Hash gerado uma vez, sob demanda, para o login de e-mail inexistente gastar
// o mesmo tempo de um login de e-mail real.
var hashFicticio = sync.OnceValue(func() string {
	hash, err := HashSenha("senha-ficticia-sem-uso")
	if err != nil {
		panic(err)
	}
	return hash
})

// VerificaSenhaFicticia roda o argon2 contra um hash descartável e sempre
// devolve false.
func VerificaSenhaFicticia(senha string) bool {
	VerificaSenha(senha, hashFicticio())
	return false
}
