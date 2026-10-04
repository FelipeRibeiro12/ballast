package http

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"mime"
	"net"
	"net/http"
	"regexp"
	"strings"
	"time"
	"unicode/utf8"
)

// Identidade usa só tipos primitivos de propósito: este pacote não importa o
// pacote de identidade, e o serviço de lá satisfaz a interface sem adaptador.
type Identidade interface {
	// criado=false quando o e-mail já existe. O e-mail chega normalizado.
	Cadastra(ctx context.Context, email, senha string, nome *string) (id string, criado bool, err error)
	// ok=false quando o e-mail não existe ou a senha não confere.
	Autentica(ctx context.Context, email, senha string) (id string, ok bool, err error)
	IniciaSessao(ctx context.Context, usuarioID string) (token string, expiraEm time.Time, err error)
	Perfil(ctx context.Context, usuarioID string) (email string, nome *string, err error)
	// ok=false quando o token é desconhecido, expirou ou foi revogado.
	UsuarioDaSessao(ctx context.Context, token string) (usuarioID string, ok bool, err error)
	// revogada=false quando não havia sessão ativa com esse token.
	RevogaSessao(ctx context.Context, token string) (revogada bool, err error)
}

const (
	limiteCorpoBytes   = 8 << 10
	emailMaxCaracteres = 254
	senhaMinCaracteres = 12
	// O máximo evita que uma senha gigante vire DoS no argon2.
	senhaMaxCaracteres = 128
	nomeMaxCaracteres  = 100
)

// Formato básico: uma arroba, sem espaço, domínio com ponto. A confirmação de
// que o endereço existe, se um dia vier, é por e-mail e não por regex.
var formatoEmail = regexp.MustCompile(`^[^@\s]+@[^@\s]+\.[^@\s]+$`)

type usuarioJSON struct {
	ID    string  `json:"id"`
	Email string  `json:"email"`
	Nome  *string `json:"nome"`
}

type cadastroRequest struct {
	Email string  `json:"email"`
	Senha string  `json:"senha"`
	Nome  *string `json:"nome"`
}

func cadastroHandler(ident Identidade) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		var req cadastroRequest
		if !leCorpoJSON(w, r, &req) {
			return
		}
		email := normalizaEmail(req.Email)
		if !emailValido(email) {
			escreveErro(w, http.StatusBadRequest, "e-mail inválido")
			return
		}
		if n := utf8.RuneCountInString(req.Senha); n < senhaMinCaracteres || n > senhaMaxCaracteres {
			escreveErro(w, http.StatusBadRequest, "senha deve ter entre 12 e 128 caracteres")
			return
		}
		nome, nomeOK := normalizaNome(req.Nome)
		if !nomeOK {
			escreveErro(w, http.StatusBadRequest, "nome deve ter no máximo 100 caracteres")
			return
		}

		id, criado, err := ident.Cadastra(r.Context(), email, req.Senha, nome)
		if err != nil {
			slog.Error("cadastro: falha ao gravar", "err", err, "ip", ipDe(r))
			escreveErro(w, http.StatusInternalServerError, "erro interno")
			return
		}
		if !criado {
			slog.Warn("cadastro recusado: e-mail já cadastrado", "email", mascaraEmail(email), "ip", ipDe(r))
			escreveErro(w, http.StatusConflict, "e-mail já cadastrado")
			return
		}
		slog.Info("cadastro criado", "usuario_id", id, "ip", ipDe(r))
		escreveJSON(w, http.StatusCreated, usuarioJSON{ID: id, Email: email, Nome: nome})
	}
}

// leCorpoJSON escreve a resposta de erro sozinha e devolve false quando a
// requisição não é JSON válido no formato esperado.
func leCorpoJSON(w http.ResponseWriter, r *http.Request, destino any) bool {
	tipo, _, err := mime.ParseMediaType(r.Header.Get("Content-Type"))
	if err != nil || tipo != "application/json" {
		escreveErro(w, http.StatusUnsupportedMediaType, "content-type deve ser application/json")
		return false
	}
	dec := json.NewDecoder(http.MaxBytesReader(w, r.Body, limiteCorpoBytes))
	dec.DisallowUnknownFields()
	if err := dec.Decode(destino); err != nil {
		escreveErro(w, http.StatusBadRequest, "corpo inválido")
		return false
	}
	// Um segundo documento depois do primeiro é lixo ou tentativa de contrabando.
	if _, err := dec.Token(); !errors.Is(err, io.EOF) {
		escreveErro(w, http.StatusBadRequest, "corpo inválido")
		return false
	}
	return true
}

func escreveErro(w http.ResponseWriter, status int, mensagem string) {
	escreveJSON(w, status, map[string]string{"erro": mensagem})
}

// A migration tem CHECK de minúsculas: normalizar aqui evita 500 e evita dois
// cadastros que diferem só pela caixa.
func normalizaEmail(email string) string {
	return strings.ToLower(strings.TrimSpace(email))
}

func emailValido(email string) bool {
	return utf8.RuneCountInString(email) <= emailMaxCaracteres && formatoEmail.MatchString(email)
}

// Nome vazio vira nulo; ok=false quando passa do limite.
func normalizaNome(nome *string) (*string, bool) {
	if nome == nil {
		return nil, true
	}
	limpo := strings.TrimSpace(*nome)
	if limpo == "" {
		return nil, true
	}
	if utf8.RuneCountInString(limpo) > nomeMaxCaracteres {
		return nil, false
	}
	return &limpo, true
}

// Para o log: o e-mail inteiro é dado pessoal, a primeira letra e o domínio
// bastam para investigar um incidente.
func mascaraEmail(email string) string {
	local, dominio, ok := strings.Cut(email, "@")
	if !ok || local == "" {
		return "***"
	}
	primeira, _ := utf8.DecodeRuneInString(local)
	return string(primeira) + "***@" + dominio
}

// RemoteAddr é o par direto da conexão; atrás de proxy passará a ser o IP do
// proxy, e aí a leitura de X-Forwarded-For precisa de decisão própria.
func ipDe(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}

type loginRequest struct {
	Email string `json:"email"`
	Senha string `json:"senha"`
}

const cookieSessao = "ballast_sessao"

// Mensagem única para e-mail inexistente e senha errada: diferenciar entrega
// quais e-mails têm conta.
const erroCredenciais = "credenciais inválidas"

func loginHandler(ident Identidade, cookieSecure bool) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		var req loginRequest
		if !leCorpoJSON(w, r, &req) {
			return
		}
		email := normalizaEmail(req.Email)
		// A política de tamanho mínimo é do cadastro; aqui só o teto, contra DoS no argon2.
		if !emailValido(email) || req.Senha == "" || utf8.RuneCountInString(req.Senha) > senhaMaxCaracteres {
			escreveErro(w, http.StatusBadRequest, "e-mail ou senha inválidos")
			return
		}

		id, ok, err := ident.Autentica(r.Context(), email, req.Senha)
		if err != nil {
			slog.Error("login: falha ao autenticar", "err", err, "ip", ipDe(r))
			escreveErro(w, http.StatusInternalServerError, "erro interno")
			return
		}
		if !ok {
			slog.Warn("login falhou", "email", mascaraEmail(email), "ip", ipDe(r))
			escreveErro(w, http.StatusUnauthorized, erroCredenciais)
			return
		}

		token, expiraEm, err := ident.IniciaSessao(r.Context(), id)
		if err != nil {
			slog.Error("login: falha ao abrir sessão", "err", err, "usuario_id", id, "ip", ipDe(r))
			escreveErro(w, http.StatusInternalServerError, "erro interno")
			return
		}
		perfilEmail, nome, err := ident.Perfil(r.Context(), id)
		if err != nil {
			slog.Error("login: falha ao ler o perfil", "err", err, "usuario_id", id, "ip", ipDe(r))
			escreveErro(w, http.StatusInternalServerError, "erro interno")
			return
		}

		// Secure só é desligado em desenvolvimento; o startup recusa false fora dele.
		http.SetCookie(w, &http.Cookie{
			Name:     cookieSessao,
			Value:    token,
			Path:     "/",
			MaxAge:   int(time.Until(expiraEm).Seconds()),
			HttpOnly: true,
			Secure:   cookieSecure,
			SameSite: http.SameSiteLaxMode,
		})
		slog.Info("login ok", "usuario_id", id, "ip", ipDe(r))
		escreveJSON(w, http.StatusOK, usuarioJSON{ID: id, Email: perfilEmail, Nome: nome})
	}
}
