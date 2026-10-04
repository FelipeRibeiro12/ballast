package identidade

import (
	"testing"

	"github.com/google/uuid"
)

func TestNovoUUIDTemVersao7(t *testing.T) {
	id, err := novoUUID()
	if err != nil {
		t.Fatalf("novoUUID: %v", err)
	}
	parsed, err := uuid.Parse(id)
	if err != nil {
		t.Fatalf("id %q não é um UUID: %v", id, err)
	}
	if parsed.Version() != 7 {
		t.Errorf("versão = %d, want 7", parsed.Version())
	}
}
