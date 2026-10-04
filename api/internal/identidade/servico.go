package identidade

import (
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"
)

// Código SQLSTATE de violação de unicidade.
const pgUniqueViolation = "23505"

// Servico assume entrada já validada e normalizada pela borda (e-mail em
// minúsculas, tamanhos checados): a regra de formato mora no handler.
type Servico struct {
	pool *pgxpool.Pool
}

func NovoServico(pool *pgxpool.Pool) *Servico { return &Servico{pool: pool} }

// Cadastra devolve criado=false quando o e-mail já existe. O hash é calculado
// antes do INSERT para que e-mail repetido e e-mail novo custem o mesmo tempo.
func (s *Servico) Cadastra(ctx context.Context, email, senha string, nome *string) (id string, criado bool, err error) {
	hash, err := HashSenha(senha)
	if err != nil {
		return "", false, err
	}
	id, err = novoUUID(time.Now())
	if err != nil {
		return "", false, err
	}
	_, err = s.pool.Exec(ctx,
		"INSERT INTO usuario (id, email, senha_hash, nome) VALUES ($1, $2, $3, $4)",
		id, email, hash, nome)
	var pgErr *pgconn.PgError
	if errors.As(err, &pgErr) && pgErr.Code == pgUniqueViolation && pgErr.ConstraintName == "usuario_email_key" {
		return "", false, nil
	}
	if err != nil {
		return "", false, err
	}
	return id, true, nil
}
