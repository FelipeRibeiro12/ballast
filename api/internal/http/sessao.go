package http

import (
	"context"
	"log/slog"
	"net/http"
)

type chaveContexto struct{}

// Mensagem única para sem cookie, token desconhecido, expirado e revogado: o
// cliente não precisa saber qual dos quatro, e diferenciar ajuda um atacante.
const erroNaoAutenticado = "não autenticado"

// exigeSessao é a única origem do usuario_id nos handlers: ele vem da sessão
// no banco, nunca de corpo, query ou cabeçalho do cliente.
func exigeSessao(ident Identidade, proximo http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		cookie, err := r.Cookie(cookieSessao)
		if err != nil || cookie.Value == "" {
			escreveErro(w, http.StatusUnauthorized, erroNaoAutenticado)
			return
		}
		usuarioID, ok, err := ident.UsuarioDaSessao(r.Context(), cookie.Value)
		if err != nil {
			slog.Error("sessão: falha ao consultar", "err", err, "ip", ipDe(r))
			escreveErro(w, http.StatusInternalServerError, "erro interno")
			return
		}
		if !ok {
			escreveErro(w, http.StatusUnauthorized, erroNaoAutenticado)
			return
		}
		proximo(w, r.WithContext(context.WithValue(r.Context(), chaveContexto{}, usuarioID)))
	}
}

// Só handler protegido por exigeSessao pode chamar; fora dele devolve vazio.
func usuarioIDDe(ctx context.Context) string {
	id, _ := ctx.Value(chaveContexto{}).(string)
	return id
}

func euHandler(ident Identidade) http.HandlerFunc {
	return exigeSessao(ident, func(w http.ResponseWriter, r *http.Request) {
		id := usuarioIDDe(r.Context())
		email, nome, err := ident.Perfil(r.Context(), id)
		if err != nil {
			slog.Error("eu: falha ao ler o perfil", "err", err, "usuario_id", id, "ip", ipDe(r))
			escreveErro(w, http.StatusInternalServerError, "erro interno")
			return
		}
		escreveJSON(w, http.StatusOK, usuarioJSON{ID: id, Email: email, Nome: nome})
	})
}

// Sem exigeSessao de propósito: logout sem sessão válida também é 204, e a
// revogação acontece no servidor, não só no cookie.
func logoutHandler(ident Identidade) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		revogada := false
		if cookie, err := r.Cookie(cookieSessao); err == nil && cookie.Value != "" {
			var errRevoga error
			revogada, errRevoga = ident.RevogaSessao(r.Context(), cookie.Value)
			if errRevoga != nil {
				// Sem confirmar a revogação não dá para dizer ao cliente que saiu.
				slog.Error("logout: falha ao revogar", "err", errRevoga, "ip", ipDe(r))
				escreveErro(w, http.StatusInternalServerError, "erro interno")
				return
			}
		}
		http.SetCookie(w, &http.Cookie{
			Name:     cookieSessao,
			Path:     "/",
			MaxAge:   -1,
			HttpOnly: true,
			Secure:   true,
			SameSite: http.SameSiteLaxMode,
		})
		slog.Info("logout", "sessao_revogada", revogada, "ip", ipDe(r))
		w.WriteHeader(http.StatusNoContent)
	}
}
