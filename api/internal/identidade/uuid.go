package identidade

import (
	"crypto/rand"
	"encoding/hex"
	"time"
)

// UUID v7 escrito à mão (RFC 9562): o Postgres 17 não tem uuidv7() e o prefixo
// de tempo mantém os índices por id mais compactos que o v4.
func novoUUID(agora time.Time) (string, error) {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		return "", err
	}
	ms := uint64(agora.UnixMilli())
	b[0], b[1], b[2] = byte(ms>>40), byte(ms>>32), byte(ms>>24)
	b[3], b[4], b[5] = byte(ms>>16), byte(ms>>8), byte(ms)
	b[6] = b[6]&0x0f | 0x70
	b[8] = b[8]&0x3f | 0x80

	var h [32]byte
	hex.Encode(h[:], b[:])
	return string(h[0:8]) + "-" + string(h[8:12]) + "-" + string(h[12:16]) + "-" + string(h[16:20]) + "-" + string(h[20:32]), nil
}
