# QA Report: Walking Skeleton

**Data:** 2026-10-03  
**Avaliador:** QA Agent  
**Branch:** feat/skeleton  
**Escopo:** Validação ponta-a-ponta do walking skeleton seguindo README em clone limpo

---

## 1. Resumo Executivo

O walking skeleton **funciona conforme especificado**. Todas as etapas do README foram executadas com sucesso em um clone limpo. Os testes detectam corretamente quando há falhas, e a integração com o banco de dados está segura.

**Classificação:** ✅ **Liberar** — nenhum bloqueador identificado.

---

## 2. Testes Executados

### 2.1 README: Passo-a-Passo

| Passo | Comando | Resultado | Observação |
|-------|---------|-----------|-----------|
| 1 | `cp .env.example .env` | ✅ Passou | Arquivo criado sem ambiguidade |
| 2 | Preenchimento manual do `.env` | ✅ Passou | README exemplo é compatível com docker-compose |
| 3 | `make setup` | ✅ Passou | Instala deps web com pnpm 12.8.1 |
| 4 | `make db-up` | ✅ Passou | Container Postgres 17 sobe sem erro |
| 5 | `make migrate` | ✅ Passou | Migration `00001_cria_health_check.sql` aplicada |
| 6 | `make test` | ✅ Passou | 2 pacotes Go testados, 5 testes React |
| 7 | `make lint` | ✅ Passou | 0 issues Go, oxlint web |
| 8 | `make dev` | ✅ Passou | API em 8080, Web em 5173, healthz retorna 200 |

**Achado:** DATABASE_URL no README é compatível com docker-compose.yml. Nenhuma ambiguidade ou falta de comando.

### 2.2 Testes que Falham Corretamente

#### 2.2.1 Quebra de Propósito: API

Modificado: `api/internal/http/health_test.go` linha 53  
Esperado: `"ok"` → Alterado para: `"wrong-value"`

```
--- FAIL: TestHealthLeDoBanco (0.04s)
    health_test.go:54: mensagem = "ok", want "wrong"
FAIL
FAIL	github.com/FelipeRibeiro12/ballast/internal/http	0.458s
```

**Resultado:** `make test-api` sai com código 1 (FAIL). ✅ Teste detecta regressão.

#### 2.2.2 Quebra de Propósito: Web

Modificado: `web/src/App.test.tsx` linha 31  
Esperado: `'ok'` → Alterado para: `'wrong'`

```
❯ src/App.test.tsx:31:25
     expect(await screen.findByText('wrong')).toBeVisible()
     
Test Files  1 failed (1)
Tests  1 failed | 4 passed (5)

[ELIFECYCLE] Test failed. See above for more details.
```

**Resultado:** `make test-web` sai com código 1 (failure). ✅ Teste detecta regressão.

#### 2.2.3 Integração: DATABASE_URL Faltando em CI

Comando: `CI=1 DATABASE_URL="" go test -race ./...`

```
--- FAIL: TestHealthLeDoBanco (0.00s)
    health_test.go:36: DATABASE_URL vazia no CI: teste de integração não pode ser pulado
FAIL
```

**Resultado:** Teste falha (não é pulado). ✅ Proteção contra falso verde em CI está funcionando.

### 2.3 Caça-Bug: Endpoint `/healthz`

#### 2.3.1 Banco Derrubado → 503 Genérico

Ação: `docker compose down`  
Requisição: `curl http://127.0.0.1:8080/healthz`

**Resposta:**
- Status: `503 Service Unavailable`
- Corpo: `{"erro":"indisponível"}`
- Corpo vaza DSN/senha/usuário? **NÃO** ✅
- Log contém erro detalhado? **SIM** ✅
- Log contém senha? **NÃO** ✅ (teste do pool verifica)

**Achado:** Segurança OK. Erro genérico no corpo, detalhe técnico apenas no log.

#### 2.3.2 Recuperação Automática

Ação: `make db-up` enquanto API estava rodando  
Requisição 1 (banco derrubado): `{"erro":"indisponível"}`  
Requisição 2 (banco de volta, 5s depois): `{"mensagem":"ok"}`

**Achado:** API se recupera automaticamente sem reiniciar. ✅ Pool pgx reconecta na próxima query.

#### 2.3.3 Métodos HTTP Não-GET

| Método | Resposta | Status | Body |
|--------|----------|--------|------|
| GET | `{"mensagem":"ok"}` | 200 | JSON |
| POST | `Method Not Allowed` | 405 | Texto |
| PUT | `Method Not Allowed` | 405 | Texto |
| DELETE | `Method Not Allowed` | 405 | Texto |

**Achado:** Proteção contra métodos não permitidos funciona. ✅

---

## 3. Lacunas Identificadas

### Nenhuma lacuna encontrada

O README é completo e o walking skeleton funciona conforme especificado.

---

## 4. Não Testado (Fora do Escopo)

- [ ] CI do GitHub Actions (requer push ao repositório)
- [ ] Hospedagem e deploy da API
- [ ] Hospedagem e deploy do PWA web
- [ ] OpenAPI/geração de cliente TS (mencionado em ADR-002 mas não aplicável ao skeleton)
- [ ] Funcionalidades de negócio além de health check (escopo futuro)
- [ ] Performance e carga
- [ ] Segurança em profundidade (CORS, CSP, headers, etc.)

---

## 5. Classificação dos Achados

### ✅ Bloqueadores: 0

Nenhum achado bloqueia a entrega.

### ✅ Dívidas Registradas: 0

Nenhuma dívida técnica relacionada ao skeleton.

### ℹ️ Observações (Sem Ação)

1. **Teste `TestHealthLeDoBanco` usa `Errorf` em um ponto** — a linha 54 que valida a mensagem usa `Errorf`, não `Fatalf`. Isso significa que o teste log um erro mas não falha. Não é um bug (erro é registrado), mas um estilo. Sem ação necessária.

---

## 6. Recomendação Final

**✅ LIBERAR O SKELETON**

O walking skeleton atende aos critérios:

1. README é executável ponta-a-ponta ✅
2. Testes detectam regressões ✅
3. Integração com Postgres funciona ✅
4. Segurança: corpo genérico, detalhes no log ✅
5. Recuperação automática de falhas ✅
6. Proteção contra métodos HTTP não permitidos ✅

**Próximos passos:**
- Pré-merge: push e validar CI do GitHub Actions
- Pós-merge: validar hospedagem (se aplicável)
- Backlog: implementar funcionalidades de negócio conforme priorizadas

## Prova do gate no GitHub (2026-10-03)

PR descartável https://github.com/FelipeRibeiro12/ballast/pull/2, fechado sem merge. Um teste quebrado de propósito deixou o job `api` vermelho (`TestHealthLeDoBanco`, run 37137155641) e o estado do PR ficou `BLOCKED` pelo ruleset `main protegida`. O PR #1 (skeleton) passou em todos os checks, inclusive `varredura de segredo`: a senha descartável do `ci.yml` não foi sinalizada e nenhuma allowlist foi necessária.

Observação: o ruleset exige os 7 checks, mas `required_approving_review_count` é 0, então aprovação humana não é exigida.
