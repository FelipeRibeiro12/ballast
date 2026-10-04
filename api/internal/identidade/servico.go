package identidade

import (
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
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

// ok=false cobre e-mail inexistente e senha errada sem distinguir. No
// inexistente roda um argon2 fictício para o tempo não denunciar a conta.
func (s *Servico) Autentica(ctx context.Context, email, senha string) (id string, ok bool, err error) {
	var hash string
	err = s.pool.QueryRow(ctx, "SELECT id, senha_hash FROM usuario WHERE email = $1", email).Scan(&id, &hash)
	if errors.Is(err, pgx.ErrNoRows) {
		return "", VerificaSenhaFicticia(senha), nil
	}
	if err != nil {
		return "", false, err
	}
	if !VerificaSenha(senha, hash) {
		return "", false, nil
	}
	return id, true, nil
}

// Sessão com prazo absoluto: não renova a cada uso, então um token roubado
// morre em 7 dias mesmo que seja usado todo dia.
const sessaoDuracao = 7 * 24 * time.Hour

// IniciaSessao devolve o token em claro, que só existe aqui e no cookie; o
// banco guarda o SHA-256 dele.
func (s *Servico) IniciaSessao(ctx context.Context, usuarioID string) (token string, expiraEm time.Time, err error) {
	token, hash, err := novoToken()
	if err != nil {
		return "", time.Time{}, err
	}
	id, err := novoUUID(time.Now())
	if err != nil {
		return "", time.Time{}, err
	}
	expiraEm = time.Now().Add(sessaoDuracao)
	_, err = s.pool.Exec(ctx,
		"INSERT INTO sessao (id, usuario_id, token_hash, expira_em) VALUES ($1, $2, $3, $4)",
		id, usuarioID, hash, expiraEm)
	if err != nil {
		return "", time.Time{}, err
	}
	return token, expiraEm, nil
}

func (s *Servico) Perfil(ctx context.Context, usuarioID string) (email string, nome *string, err error) {
	err = s.pool.QueryRow(ctx, "SELECT email, nome FROM usuario WHERE id = $1", usuarioID).Scan(&email, &nome)
	return email, nome, err
}

// A busca é pelo hash do token: o banco nunca recebe o token em claro.
func (s *Servico) UsuarioDaSessao(ctx context.Context, token string) (usuarioID string, ok bool, err error) {
	err = s.pool.QueryRow(ctx,
		"SELECT usuario_id FROM sessao WHERE token_hash = $1 AND revogada_em IS NULL AND expira_em > $2",
		hashDoToken(token), time.Now()).Scan(&usuarioID)
	if errors.Is(err, pgx.ErrNoRows) {
		return "", false, nil
	}
	if err != nil {
		return "", false, err
	}
	return usuarioID, true, nil
}

// O filtro revogada_em IS NULL preserva o instante do primeiro logout.
func (s *Servico) RevogaSessao(ctx context.Context, token string) (bool, error) {
	tag, err := s.pool.Exec(ctx,
		"UPDATE sessao SET revogada_em = $2 WHERE token_hash = $1 AND revogada_em IS NULL",
		hashDoToken(token), time.Now())
	if err != nil {
		return false, err
	}
	return tag.RowsAffected() == 1, nil
}
