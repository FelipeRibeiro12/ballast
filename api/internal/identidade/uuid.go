package identidade

import "github.com/google/uuid"

// UUID v7: o Postgres 17 não tem uuidv7() e o prefixo de tempo mantém os
// índices por id mais compactos que o v4.
func novoUUID() (string, error) {
	id, err := uuid.NewV7()
	if err != nil {
		return "", err
	}
	return id.String(), nil
}
