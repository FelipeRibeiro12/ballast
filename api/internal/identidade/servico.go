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
	pool       *pgxpool.Pool
	parametros ParametrosArgon
	agora      func() time.Time
}

type Opcao func(*Servico)

// ComParametrosDeHash troca os parâmetros do argon2 no cadastro. É para testes:
// o main não passa opções e usa os de produção.
func ComParametrosDeHash(p ParametrosArgon) Opcao {
	return func(s *Servico) { s.parametros = p }
}

// ComRelogio troca a fonte de hora do atraso de login. É para testes: o atraso
// chega a 15 minutos e o teste não pode dormir.
func ComRelogio(agora func() time.Time) Opcao {
	return func(s *Servico) { s.agora = agora }
}

func NovoServico(pool *pgxpool.Pool, opcoes ...Opcao) *Servico {
	s := &Servico{pool: pool, parametros: parametrosProducao, agora: time.Now}
	for _, aplica := range opcoes {
		aplica(s)
	}
	return s
}

// Cadastra devolve criado=false quando o e-mail já existe. O hash é calculado
// antes do INSERT para que e-mail repetido e e-mail novo custem o mesmo tempo.
func (s *Servico) Cadastra(ctx context.Context, email, senha string, nome *string) (id string, criado bool, err error) {
	hash, err := HashSenhaCom(senha, s.parametros)
	if err != nil {
		return "", false, err
	}
	id, err = novoUUID()
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

// Atraso progressivo por e-mail digitado: até 3 falhas seguidas sem atraso; da
// 4ª em diante, 2^(falhas-3) segundos, com teto.
const (
	falhasSemAtraso = 3
	atrasoMaximo    = 15 * time.Minute
)

// atrasoApos devolve o atraso exigido depois da última falha. O expoente é
// limitado antes do deslocamento: com falhas grande, 1<<expoente estouraria.
func atrasoApos(falhas int) time.Duration {
	if falhas <= falhasSemAtraso {
		return 0
	}
	expoente := falhas - falhasSemAtraso
	if expoente >= 10 { // 2^10 s já passa de 15 min
		return atrasoMaximo
	}
	return min(time.Duration(1<<expoente)*time.Second, atrasoMaximo)
}

// Autentica devolve espera > 0 quando a conta está no atraso: nesse caso não
// verifica a senha nem mexe na contagem, senão um atacante manteria o dono
// trancado. A chave é o e-mail digitado, existindo conta ou não. falhas é a
// contagem corrente, para o log.
// ok=false cobre e-mail inexistente e senha errada sem distinguir. No
// inexistente roda um argon2 fictício para o tempo não denunciar a conta.
//
// A tentativa é reservada (contada) antes do argon2, num único comando: é isso
// que faz tentativas simultâneas contarem uma a uma. Se o banco falhar depois
// da reserva, o contador já subiu e fica assim (fail-closed).
func (s *Servico) Autentica(ctx context.Context, email, senha string) (id string, ok bool, espera time.Duration, falhas int, err error) {
	reservada, falhas, err := s.reservaTentativa(ctx, email)
	if err != nil {
		return "", false, 0, 0, err
	}
	if !reservada {
		espera, falhas, err = s.esperaRestante(ctx, email)
		if err != nil {
			return "", false, 0, 0, err
		}
		// O atraso pode vencer entre a recusa e a leitura; recusado é recusado.
		return "", false, max(espera, time.Millisecond), falhas, nil
	}

	var hash string
	err = s.pool.QueryRow(ctx, "SELECT id, senha_hash FROM usuario WHERE email = $1", email).Scan(&id, &hash)
	switch {
	case errors.Is(err, pgx.ErrNoRows):
		ok = VerificaSenhaFicticia(senha)
	case err != nil:
		return "", false, 0, 0, err
	default:
		ok = VerificaSenha(senha, hash)
	}

	if !ok {
		// Já contada na reserva: não incrementa de novo.
		return "", false, 0, falhas, nil
	}
	if _, err = s.pool.Exec(ctx, "DELETE FROM tentativa_login WHERE email = $1", email); err != nil {
		return "", false, 0, 0, err
	}
	return id, true, 0, 0, nil
}

// reservaTentativa é um único UPSERT: ou a linha está fora do atraso e o
// incremento acontece (reservada=true), ou está dentro e nada muda, sem linha
// no RETURNING. O WHERE repete atrasoApos em SQL; os testes de atraso cobrem
// as duas pontas.
//
// A hora vem sempre do relógio da aplicação ($2), nunca de now() do banco:
// ultima_falha_em e a comparação com o atraso usam a mesma fonte, e é ela que
// o teste controla. Misturar as duas faria o atraso depender do desvio entre
// os relógios.
func (s *Servico) reservaTentativa(ctx context.Context, email string) (reservada bool, falhas int, err error) {
	err = s.pool.QueryRow(ctx, `
		INSERT INTO tentativa_login (email, falhas, ultima_falha_em) VALUES ($1, 1, $2)
		ON CONFLICT (email) DO UPDATE
		SET falhas = tentativa_login.falhas + 1, ultima_falha_em = EXCLUDED.ultima_falha_em
		WHERE tentativa_login.falhas <= $3
		   OR tentativa_login.ultima_falha_em
		      + make_interval(secs => least(power(2, least(tentativa_login.falhas - $3, 10)), $4)) <= EXCLUDED.ultima_falha_em
		RETURNING falhas`,
		email, s.agora(), falhasSemAtraso, atrasoMaximo.Seconds()).Scan(&falhas)
	if errors.Is(err, pgx.ErrNoRows) {
		return false, 0, nil
	}
	if err != nil {
		return false, 0, err
	}
	return true, falhas, nil
}

func (s *Servico) esperaRestante(ctx context.Context, email string) (espera time.Duration, falhas int, err error) {
	var ultima time.Time
	err = s.pool.QueryRow(ctx,
		"SELECT falhas, ultima_falha_em FROM tentativa_login WHERE email = $1", email).Scan(&falhas, &ultima)
	if errors.Is(err, pgx.ErrNoRows) {
		return 0, 0, nil
	}
	if err != nil {
		return 0, 0, err
	}
	// Estritamente menor: no instante exato do fim do atraso a tentativa já passa.
	if restante := ultima.Add(atrasoApos(falhas)).Sub(s.agora()); restante > 0 {
		return restante, falhas, nil
	}
	return 0, falhas, nil
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
	id, err := novoUUID()
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
