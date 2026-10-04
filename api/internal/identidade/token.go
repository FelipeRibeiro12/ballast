package identidade

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
)

const tokenBytes = 32

// 256 bits aleatórios: não há espaço de busca para um hash lento proteger, por
// isso SHA-256 e não argon2 (ADR-015).
func novoToken() (token string, hash []byte, err error) {
	b := make([]byte, tokenBytes)
	if _, err := rand.Read(b); err != nil {
		return "", nil, err
	}
	token = base64.RawURLEncoding.EncodeToString(b)
	return token, hashDoToken(token), nil
}

func hashDoToken(token string) []byte {
	h := sha256.Sum256([]byte(token))
	return h[:]
}
