// Comando seed cria o usuário inicial a partir do ambiente. O cadastro pela
// API fica fechado por padrão, então este é o caminho de entrada do primeiro
// usuário.
package main

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"os"
	"os/signal"
	"regexp"
	"strings"
	"syscall"
	"unicode/utf8"

	"github.com/FelipeRibeiro12/ballast/internal/identidade"
	"github.com/jackc/pgx/v5/pgxpool"
)

// Mesma regra do cadastro pela API (internal/http). Fica duplicada em vez de
// compartilhada porque um pacote interno não importa o outro.
var formatoEmail = regexp.MustCompile(`^[^@\s]+@[^@\s]+\.[^@\s]+$`)

func main() {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	if err := run(ctx, os.Getenv, os.Stdout); err != nil {
		slog.Error("seed falhou", "err", err)
		os.Exit(1)
	}
}

// run nunca escreve a senha nem o e-mail na saída ou nos erros.
func run(ctx context.Context, getenv func(string) string, out io.Writer) error {
	email := strings.ToLower(strings.TrimSpace(getenv("SEED_EMAIL")))
	senha := getenv("SEED_SENHA")
	if email == "" || senha == "" {
		return errors.New("SEED_EMAIL e SEED_SENHA são obrigatórias")
	}
	if utf8.RuneCountInString(email) > 254 || !formatoEmail.MatchString(email) {
		return errors.New("SEED_EMAIL não é um e-mail válido")
	}
	if n := utf8.RuneCountInString(senha); n < 12 || n > 128 {
		return errors.New("SEED_SENHA deve ter entre 12 e 128 caracteres")
	}
	var nome *string
	if n := strings.TrimSpace(getenv("SEED_NOME")); n != "" {
		if utf8.RuneCountInString(n) > 100 {
			return errors.New("SEED_NOME deve ter no máximo 100 caracteres")
		}
		nome = &n
	}
	databaseURL := getenv("DATABASE_URL")
	if databaseURL == "" {
		return errors.New("DATABASE_URL não definida")
	}

	pool, err := pgxpool.New(ctx, databaseURL)
	if err != nil {
		// Não embrulha a URL: ela carrega a senha do banco.
		return errors.New("cria pool do banco: URL inválida")
	}
	defer pool.Close()

	id, criado, err := identidade.NovoServico(pool).Cadastra(ctx, email, senha, nome)
	if err != nil {
		return fmt.Errorf("cria usuário: %w", err)
	}
	if !criado {
		_, err = fmt.Fprintln(out, "usuário já existe, nada feito")
		return err
	}
	_, err = fmt.Fprintf(out, "usuário criado (id %s)\n", id)
	return err
}
