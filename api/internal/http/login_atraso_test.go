package http

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/FelipeRibeiro12/ballast/internal/identidade"
)

// Relógio que só anda quando o teste manda: o atraso chega a 15 minutos.
type relogioFalso struct {
	mu sync.Mutex
	t  time.Time
}

func novoRelogio() *relogioFalso {
	return &relogioFalso{t: time.Date(2026, 3, 10, 12, 0, 0, 0, time.UTC)}
}

func (r *relogioFalso) agora() time.Time {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.t
}

func (r *relogioFalso) avanca(d time.Duration) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.t = r.t.Add(d)
}

func (r *relogioFalso) defineEm(t time.Time) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.t = t
}

func ambienteComRelogio(t *testing.T) (*ambiente, *relogioFalso) {
	t.Helper()
	pool := poolDeTeste(t)
	rel := novoRelogio()
	svc := identidade.NovoServico(pool, identidade.ComParametrosDeHash(hashBarato), identidade.ComRelogio(rel.agora))
	return &ambiente{pool: pool, h: NewRouter(pool, svc, true, true)}, rel
}

func (a *ambiente) tenta(email, senha string) *httptest.ResponseRecorder {
	_, rec := postJSON(a.h, "/auth/login", map[string]string{"email": email, "senha": senha})
	return rec
}

const senhaErrada = "senha-errada-12345"

func (a *ambiente) falhasGravadas(t *testing.T, chave string) (int, bool) {
	t.Helper()
	var n int
	err := a.pool.QueryRow(context.Background(), "SELECT falhas FROM tentativa_login WHERE email = $1", chave).Scan(&n)
	if err != nil {
		if strings.Contains(err.Error(), "no rows") {
			return 0, false
		}
		t.Fatalf("lê tentativa_login: %v", err)
	}
	return n, true
}

func exigeStatus(t *testing.T, rec *httptest.ResponseRecorder, want int, contexto string) {
	t.Helper()
	if rec.Code != want {
		t.Fatalf("%s: status = %d, want %d (corpo: %s)", contexto, rec.Code, want, rec.Body)
	}
}

func TestLoginTresFalhasNaoTemAtrasoEAQuartaAtivaOAtraso(t *testing.T) {
	a, _ := ambienteComRelogio(t)
	email := emailUnico(t, a.pool)
	a.cadastra(t, email)

	// A 4ª tentativa ainda é verificada: o atraso nasce da 4ª falha, não antes dela.
	for i := 1; i <= 4; i++ {
		exigeStatus(t, a.tenta(email, senhaErrada), http.StatusUnauthorized, "tentativa "+strconv.Itoa(i))
	}
	rec := a.tenta(email, senhaErrada)
	exigeStatus(t, rec, http.StatusTooManyRequests, "5ª tentativa")
	if got := rec.Header().Get("Retry-After"); got != "2" {
		t.Errorf("Retry-After = %q, want 2 (4ª falha -> 2 s)", got)
	}
	if want := `{"erro":"muitas tentativas, tente mais tarde"}` + "\n"; rec.Body.String() != want {
		t.Errorf("corpo = %q, want %q", rec.Body, want)
	}
	if len(rec.Header().Values("Set-Cookie")) != 0 {
		t.Error("429 definiu cookie")
	}
}

func TestLoginDuranteOAtrasoNaoIncrementaNemVerificaASenha(t *testing.T) {
	a, _ := ambienteComRelogio(t)
	email := emailUnico(t, a.pool)
	a.cadastra(t, email)
	for range 4 {
		a.tenta(email, senhaErrada)
	}

	for range 5 {
		exigeStatus(t, a.tenta(email, senhaErrada), http.StatusTooManyRequests, "durante o atraso")
	}
	// Com a senha certa também: se verificasse, abriria sessão.
	rec := a.tenta(email, senhaDeTeste)
	exigeStatus(t, rec, http.StatusTooManyRequests, "senha certa durante o atraso")
	if len(rec.Header().Values("Set-Cookie")) != 0 {
		t.Error("abriu sessão durante o atraso")
	}
	if n, _ := a.falhasGravadas(t, email); n != 4 {
		t.Errorf("falhas = %d, want 4: o 429 não pode incrementar (senão o atacante tranca o dono)", n)
	}
}

func TestLoginDepoisDoAtrasoVoltaAVerificar(t *testing.T) {
	a, rel := ambienteComRelogio(t)
	email := emailUnico(t, a.pool)
	a.cadastra(t, email)
	for range 4 {
		a.tenta(email, senhaErrada)
	}

	rel.avanca(1500 * time.Millisecond)
	rec := a.tenta(email, senhaErrada)
	exigeStatus(t, rec, http.StatusTooManyRequests, "faltando 0,5 s")
	if got := rec.Header().Get("Retry-After"); got != "1" {
		t.Errorf("Retry-After = %q, want 1 (0,5 s arredonda para cima)", got)
	}

	// Em ultima_falha_em + atraso exatos a janela já acabou.
	rel.avanca(500 * time.Millisecond)
	exigeStatus(t, a.tenta(email, senhaErrada), http.StatusUnauthorized, "no fim exato do atraso")
	if n, _ := a.falhasGravadas(t, email); n != 5 {
		t.Errorf("falhas = %d, want 5", n)
	}
	rec = a.tenta(email, senhaErrada)
	exigeStatus(t, rec, http.StatusTooManyRequests, "5ª falha")
	if got := rec.Header().Get("Retry-After"); got != "4" {
		t.Errorf("Retry-After = %q, want 4 (5ª falha -> 4 s)", got)
	}

	rel.avanca(4 * time.Second)
	exigeStatus(t, a.tenta(email, senhaDeTeste), http.StatusOK, "senha certa depois do atraso")
}

func TestLoginComSucessoZeraAContagem(t *testing.T) {
	a, _ := ambienteComRelogio(t)
	email := emailUnico(t, a.pool)
	a.cadastra(t, email)

	for range 3 {
		a.tenta(email, senhaErrada)
	}
	exigeStatus(t, a.tenta(email, senhaDeTeste), http.StatusOK, "sucesso")
	if n, existe := a.falhasGravadas(t, email); existe {
		t.Errorf("falhas = %d depois do sucesso, want linha apagada", n)
	}
	// Se não zerasse, a próxima falha seria a 4ª e a seguinte já cairia em 429.
	for i := 1; i <= 4; i++ {
		exigeStatus(t, a.tenta(email, senhaErrada), http.StatusUnauthorized, "falha "+strconv.Itoa(i)+" depois do zerar")
	}
}

func TestLoginAtrasoTemTetoDeQuinzeMinutos(t *testing.T) {
	a, rel := ambienteComRelogio(t)
	casos := []struct {
		falhas int
		want   string
	}{
		{4, "2"}, {5, "4"}, {6, "8"}, {12, "512"},
		{13, "900"},         // 2^10 = 1024 s passaria do teto
		{70, "900"},         // 2^67 estouraria o int64 se o expoente não fosse limitado
		{2000000000, "900"}, // perto do limite do int
	}
	for _, c := range casos {
		email := emailUnico(t, a.pool)
		_, err := a.pool.Exec(context.Background(),
			"INSERT INTO tentativa_login (email, falhas, ultima_falha_em) VALUES ($1, $2, $3)", email, c.falhas, rel.agora())
		if err != nil {
			t.Fatalf("prepara tentativa_login: %v", err)
		}
		rec := a.tenta(email, senhaErrada)
		exigeStatus(t, rec, http.StatusTooManyRequests, "falhas="+strconv.Itoa(c.falhas))
		if got := rec.Header().Get("Retry-After"); got != c.want {
			t.Errorf("falhas=%d: Retry-After = %q, want %s", c.falhas, got, c.want)
		}
	}

	// Com o teto, 15 min depois da última falha a conta volta a responder.
	email := emailUnico(t, a.pool)
	_, _ = a.pool.Exec(context.Background(),
		"INSERT INTO tentativa_login (email, falhas, ultima_falha_em) VALUES ($1, 50, $2)", email, rel.agora())
	rel.avanca(899 * time.Second)
	exigeStatus(t, a.tenta(email, senhaErrada), http.StatusTooManyRequests, "faltando 1 s")
	rel.avanca(time.Second)
	exigeStatus(t, a.tenta(email, senhaErrada), http.StatusUnauthorized, "15 min depois")
}

type observado struct {
	status     int
	corpo      string
	retryAfter string
	cabecalhos string
}

// Roda a mesma sequência a partir do mesmo instante e registra só o que o
// cliente enxerga.
func sequenciaObservada(a *ambiente, rel *relogioFalso, email string) []observado {
	rel.defineEm(time.Date(2026, 3, 10, 12, 0, 0, 0, time.UTC))
	var saida []observado
	passos := []time.Duration{0, 0, 0, 0, 0, time.Second, time.Second, 0, 2 * time.Second, 0, 3 * time.Second, 0}
	for _, avanco := range passos {
		rel.avanca(avanco)
		rec := a.tenta(email, senhaErrada)
		h, _ := json.Marshal(rec.Header())
		saida = append(saida, observado{rec.Code, rec.Body.String(), rec.Header().Get("Retry-After"), string(h)})
	}
	return saida
}

func TestLoginEmailInexistenteSeComportaIgualAoExistente(t *testing.T) {
	a, rel := ambienteComRelogio(t)
	existente := emailUnico(t, a.pool)
	a.cadastra(t, existente)
	inexistente := emailUnico(t, a.pool)

	comConta := sequenciaObservada(a, rel, existente)
	semConta := sequenciaObservada(a, rel, inexistente)

	vistos429 := 0
	for i := range comConta {
		if comConta[i] != semConta[i] {
			t.Errorf("passo %d difere: com conta %+v, sem conta %+v", i, comConta[i], semConta[i])
		}
		if comConta[i].status == http.StatusTooManyRequests {
			vistos429++
		}
	}
	if vistos429 == 0 {
		t.Fatal("a sequência não chegou a nenhum 429: o teste não provou nada")
	}
	if n, ok := a.falhasGravadas(t, inexistente); !ok || n == 0 {
		t.Error("e-mail inexistente não foi contado")
	}
}

func TestLoginVariacaoDeCaixaCaiNaMesmaChave(t *testing.T) {
	a, _ := ambienteComRelogio(t)
	email := emailUnico(t, a.pool)
	a.cadastra(t, email)

	variacoes := []string{email, strings.ToUpper(email), " " + email + " ", strings.ToUpper(email[:3]) + email[3:]}
	for _, v := range variacoes {
		exigeStatus(t, a.tenta(v, senhaErrada), http.StatusUnauthorized, "variação "+v)
	}
	exigeStatus(t, a.tenta(strings.ToUpper(email), senhaErrada), http.StatusTooManyRequests, "5ª, em maiúsculas")
	if n, _ := a.falhasGravadas(t, email); n != 4 {
		t.Errorf("falhas na chave normalizada = %d, want 4", n)
	}
	var linhas int
	_ = a.pool.QueryRow(context.Background(), "SELECT count(*) FROM tentativa_login WHERE lower(email) = $1", email).Scan(&linhas)
	if linhas != 1 {
		t.Errorf("linhas = %d, want 1", linhas)
	}
}

func TestLoginRegistraIncrementoE429NoLogSemSegredo(t *testing.T) {
	a, _ := ambienteComRelogio(t)
	email := emailUnico(t, a.pool)
	a.cadastra(t, email)
	logBuf := capturaLog(t)

	for range 5 {
		a.tenta(email, senhaErrada)
	}

	log := logBuf.String()
	for _, want := range []string{"falhas=4", "t***@exemplo.test", "192.0.2.1", "retry_after=2"} {
		if !strings.Contains(log, want) {
			t.Errorf("log sem %q: %s", want, log)
		}
	}
	if !strings.Contains(log, "level=WARN") || !strings.Contains(log, "muitas tentativas") {
		t.Errorf("log sem o registro do 429: %s", log)
	}
	for _, proibido := range []string{email, senhaErrada, senhaDeTeste} {
		if strings.Contains(log, proibido) {
			t.Errorf("log contém %q", proibido)
		}
	}
}

// Reserva atômica: logins simultâneos para o mesmo e-mail contam um a um. Sem
// ela, todos passam pela checagem antes de qualquer falha ser registrada e o
// limite de 4 verificações vale por lote, não por tentativa.
func TestLoginSimultaneoNaoVerificaMaisQueOLimite(t *testing.T) {
	const tentativas = 20
	const limite = falhasLivresMaisUma

	for _, caso := range []struct {
		nome   string
		existe bool
	}{{"e-mail existente", true}, {"e-mail inexistente", false}} {
		t.Run(caso.nome, func(t *testing.T) {
			a, _ := ambienteComRelogio(t) // relógio parado: o atraso não vence no meio do teste
			email := emailUnico(t, a.pool)
			if caso.existe {
				a.cadastra(t, email)
			}

			codigos := make(chan int, tentativas)
			largada := make(chan struct{})
			var wg sync.WaitGroup
			for range tentativas {
				wg.Add(1)
				go func() {
					defer wg.Done()
					<-largada
					codigos <- a.tenta(email, senhaErrada).Code
				}()
			}
			close(largada)
			wg.Wait()
			close(codigos)

			contagem := map[int]int{}
			for c := range codigos {
				contagem[c]++
			}
			// 401 só sai depois de verificar a senha: é a contagem de verificações.
			if contagem[http.StatusUnauthorized] != limite {
				t.Errorf("401 = %d, want exatamente %d (as reservas concedidas); códigos: %v", contagem[http.StatusUnauthorized], limite, contagem)
			}
			if contagem[http.StatusUnauthorized]+contagem[http.StatusTooManyRequests] != tentativas {
				t.Errorf("há resposta que não é 401 nem 429: %v", contagem)
			}
			n, _ := a.falhasGravadas(t, email)
			if n != contagem[http.StatusUnauthorized] {
				t.Errorf("falhas = %d, want %d (uma por reserva concedida)", n, contagem[http.StatusUnauthorized])
			}
		})
	}
}

// 3 falhas livres mais a 4ª, que ainda é verificada e ativa o atraso.
const falhasLivresMaisUma = 4
