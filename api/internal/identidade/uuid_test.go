package identidade

import (
	"regexp"
	"testing"
	"time"
)

var formatoUUIDv7 = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$`)

func TestNovoUUIDTemVersao7EVarianteRFC(t *testing.T) {
	id, err := novoUUID(time.Now())
	if err != nil {
		t.Fatalf("novoUUID: %v", err)
	}
	if !formatoUUIDv7.MatchString(id) {
		t.Errorf("id = %q, não é um UUID v7 válido", id)
	}
}

func TestNovoUUIDCarregaOTimestampEmMilissegundos(t *testing.T) {
	agora := time.UnixMilli(1_700_000_000_123)
	id, err := novoUUID(agora)
	if err != nil {
		t.Fatalf("novoUUID: %v", err)
	}
	// 1_700_000_000_123 ms = 0x018BCFE5687B.
	if got, want := id[:13], "018bcfe5-687b"; got != want {
		t.Errorf("prefixo = %q, want %q", got, want)
	}
}

func TestNovoUUIDOrdenaPeloTempo(t *testing.T) {
	base := time.UnixMilli(1_700_000_000_000)
	anterior := ""
	for i := range 50 {
		id, err := novoUUID(base.Add(time.Duration(i) * time.Millisecond))
		if err != nil {
			t.Fatalf("novoUUID: %v", err)
		}
		if id <= anterior {
			t.Fatalf("id %q não é maior que o anterior %q", id, anterior)
		}
		anterior = id
	}
}

func TestNovoUUIDNaoRepete(t *testing.T) {
	visto := map[string]bool{}
	agora := time.Now()
	for range 1000 {
		id, err := novoUUID(agora)
		if err != nil {
			t.Fatalf("novoUUID: %v", err)
		}
		if visto[id] {
			t.Fatalf("id repetido: %s", id)
		}
		visto[id] = true
	}
}
