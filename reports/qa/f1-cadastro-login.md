# Relatório QA — F1: Cadastro, Login, Logout e GET /auth/eu

**Data:** 2026-10-04 (2ª rodada)  
**Testador:** Claude QA (Haiku 4.5)

## Escopo Testado

- POST /auth/cadastro (com/sem registro aberto)
- POST /auth/login (credenciais válidas, inválidas, caracteres de controle)
- GET /auth/eu (sessão válida, expirada, revogada)
- POST /auth/logout (revogação de sessão)
- Validação de entrada (tamanho, tipo, campos, caracteres de controle)
- Sessões independentes (dois logins = dois tokens)
- Atraso progressivo e rate limiting (429 com Retry-After)
- Segurança de sessão (token_hash é SHA-256, expiração, concorrência)

## Suíte de Testes

```
make test
  Go:  108 testes (api/cmd/seed, api/cmd/api, api/internal/http, api/internal/identidade)
  Web: 36 testes (jsdom)
  Total: 144 testes
make lint: 0 issues
```

Todos os testes passaram antes e depois dos testes de QA.

## Achados

### 1. POST /auth/cadastro com REGISTRO_ABERTO=false

**Status:** Não reproduzido na 2ª rodada

**O que aconteceu na 1ª rodada:**
Não foi possível reproduzir de verdade na 1ª rodada — a porta 8080 foi usada, provavelmente observando servidor antigo e não o binário testado.

**O que foi testado na 2ª rodada (verificação limpa):**
```
REGISTRO_ABERTO=false PORT=18100 COOKIE_SECURE=false APP_ENV=development /tmp/api_qa
PID 8757, porta confirmada (18100), variáveis de env confirmadas

POST /auth/cadastro   → 404 (idêntico a rota inexistente)
GET /auth/cadastro    → 404
DELETE /auth/cadastro → 404
/notarealpath         → 404 (mesmo cabeçalho, corpo idêntico)

REGISTRO_ABERTO=true:
POST /auth/cadastro   → 201 (usuário criado com id, email)
```

**Evidência:** O código em api/internal/http/router.go condiciona a rota ao valor de `REGISTRO_ABERTO`. A rota não é registrada quando false, retornando 404 de não existência para qualquer método. Comportamento correto.

---

### 2. IMPORTANTE (2ª rodada): Caracteres de controle em email/nome

**Status:** CORRIGIDO ✓

**Teste: Caracteres obrigatórios rejeitados com 400**

Cadastro com controle no email:
```
NUL (\u0000)     → 400 "corpo inválido" (erro no parse JSON)
BEL (\u0007)     → 400 "corpo inválido"
\n (newline)     → 400 "corpo inválido"
DEL (\u007f)     → 400 "e-mail inválido"
U+0085 (NEL)     → 400 "e-mail inválido"
U+2028 (sep lin) → 400 "e-mail inválido"
U+2029 (sep par) → 400 "e-mail inválido"
```

Login com controle no email:
```
Todos os acima    → 400
tentativa_login   → 0 linhas criadas (validação anterior ao banco)
```

Cadastro com controle no nome: mesmo padrão (400).

**Observação: Caracteres de categoria Cf passam (esperado)**
```
U+200B (zero-width space)           → PASSA (criado com sucesso)
U+202E (right-to-left override)     → PASSA
U+FEFF (zero-width no-break space)  → PASSA
```

A validação rejeita categoria Cc (controle) + U+2028/U+2029, que é correto pois Cf (format) não quebra estrutura.

Evidência: api/internal/http/controle_test.go contém 6 testes (NUL, BEL, \n, DEL, NEL, L/P separators) no cadastro, login e verificação de tentativa_login. Commit 42ecec6 adicionou função `temControle()` que valida antes do banco.

---

## Testes Que Passaram

### Caminho feliz
- ✓ Cadastro com sucesso (201, {id, email, nome})
- ✓ Login com credenciais corretas (200, Set-Cookie com HttpOnly/SameSite)
- ✓ GET /auth/eu com sessão válida (200)
- ✓ Logout revoga sessão (204, GET /auth/eu retorna 401)

### Sessões
- ✓ Token_hash é SHA-256 do token (32 bytes no banco = 256 bits, confirmado por: token cookie MFDl02W4... → SHA-256: 3052592e... → banco: 32 bytes \x3052592e...)
- ✓ Sessão expirada retorna 401 (alterado expira_em no banco para -1 dia, GET /auth/eu 200→401)
- ✓ Token revogado retorna 401
- ✓ Token adulterado retorna 401
- ✓ Dois logins geram tokens diferentes
- ✓ Logout de uma sessão não afeta outra

### Enumeração
- ✓ E-mail inexistente e senha errada: mesmo 401, mesmo corpo "credenciais inválidas"
- ✓ Timing sem diferença detectável (ambos ~100-150ms)

### Atraso progressivo
- ✓ Falhas 1-3: 401
- ✓ Falha 4+: 429 com Retry-After
- ✓ Sucesso reseta contador
- ✓ Concorrência: 20 logins falhos simultâneos = 20 falhas no contador (sem race condition, confirmado por SELECT COUNT(*) FROM tentativa_login)

### Validação de entrada
- ✓ Campo desconhecido rejeitado (400)
- ✓ Senha fora de [12, 128] rejeitada (400)
- ✓ E-mail vazio ou > 254 caracteres rejeitado (400)
- ✓ Nome > 100 caracteres rejeitado (400)
- ✓ Content-Type != application/json rejeitado (415)
- ✓ JSON malformado rejeitado (400)
- ✓ Null em campo obrigatório rejeitado (400)
- ✓ Tipo errado rejeitado (400)
- ✓ Corpo vazio rejeitado (400)
- ✓ Corpo > 8 KiB rejeitado (400)

### Segurança
- ✓ Senhas não aparecem em logs
- ✓ Tokens e hashes não aparecem em logs
- ✓ E-mails não aparecem em logs
- ✓ Cookie HttpOnly presente
- ✓ Cookie SameSite=Lax presente
- ✓ Senha com caracteres de controle é aceita (não validada)

### Compliance OpenAPI
- ✓ POST /auth/cadastro retorna {id, email, nome}
- ✓ POST /auth/login retorna {id, email, nome}
- ✓ GET /auth/eu retorna {id, email, nome}
- ✓ E-mail duplicado retorna 409
- ✓ POST /auth/logout retorna 204

---

## O que estes testes NÃO cobrem

1. **Browser real:** Testes executados em jsdom (Node.js). Não foi verificado comportamento em Chrome, Firefox, Safari (cookies HTTPOnly, SameSite em diferentes navegadores, localStorage vs. sessionStorage, redirecionamentos).

2. **Integração web-API:** Web testado em jsdom com mock HTTP. Não foi rodado navegador apontando para API real, observando fluxo completo de login/logout (incluindo redirect, refresh de página, re-autenticação).

3. **Observabilidade de caracteres invisíveis (Cf/bidi):** Caracteres de categoria Cf (formato) passam validação. U+200B (zero-width space), U+202E (right-to-left override) e U+FEFF (zero-width no-break space) estão presentes na entrada e no banco; seu impacto em UX, exibição em logs e busca não foram testados.

4. **Validação de controle não verificada em seed:** Credenciais passadas via variável de ambiente (SEED_EMAIL, SEED_SENHA, SEED_NAME) através de `cmd/seed`. Não foi testado se seed rejeita caracteres de controle no email/nome ou se a função temControle() é chamada durante o seeding.

5. **Resposta em caso de banco indisponível:** Postgres estava sempre disponível. Não foi testado GET /auth/eu ou POST /auth/logout com banco offline (503 vs 500).

6. **Comportamento de proxy/load balancer:** Testes contra localhost. X-Forwarded-For e detecção de IP real não foram testados. Rate limiting por IP é só por 127.0.0.1.

7. **Limpeza de tentativa_login:** Tabela cresce indefinidamente. Não há teste de retenção, limite de tamanho, ou limpeza de entradas antigas.

8. **Comportamento pós-429:** Após 429 com Retry-After=2, não foi testado se requisição imediata é bloqueada ou se só 2+ segundos destravam (teste atual só verifica que Retry-After diminui, não que é respeitado no cliente).

---

## Recomendação

**Liberar.**

- **Achado real corrigido:** Um bloqueante existiu — caracteres de controle em email/nome retornavam 500. Corrigido em 42ecec6. Validação abrangia NUL, BEL, \n, DEL, NEL, U+2028/U+2029.
- **Evidência:** Todos os testes de QA passaram, incluindo rigor em concorrência e expiração.
- **Próximos passos:** Testes em navegador real (F1 web) e rodada de integração com backend antes da F2.

---

**Hash de commit testado:** `42ecec6` (fix: rejeita caractere de controle em e-mail e nome com 400 antes do banco)
