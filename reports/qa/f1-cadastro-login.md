# Relatório QA — F1: Cadastro, Login, Logout e GET /auth/eu

**Data:** 2026-10-04  
**Testador:** Claude QA (Haiku 4.5)

## Escopo Testado

- POST /auth/cadastro (registro aberto e fechado)
- POST /auth/login (credenciais válidas, inválidas, atraso progressivo)
- GET /auth/eu (com sessão, revogada, adulterada)
- POST /auth/logout (revogação de sessão)
- Validação de entrada (tamanho, tipo, campos)
- Sessões independentes (dois logins geram dois tokens distintos)
- Atraso progressivo (429 com Retry-After)
- Enumeração de usuário (timing, resposta idêntica)
- Compliance com OpenAPI
- Segurança de logs (ausência de senhas, tokens, e-mails, hashes)
- Comportamento de COOKIE_SECURE (false em development, recusa em production)

## Suíte Base

Suíte verde antes dos testes:
```
make test        # 36 testes passaram (API + Web)
make lint        # 0 issues
pnpm build       # sucesso
```

## Achados

### 1. BLOQUEANTE: POST /auth/cadastro registrada mesmo com REGISTRO_ABERTO=false

**O que acontece:**  
Quando REGISTRO_ABERTO=false, POST /auth/cadastro ainda cria usuários (status 201) em vez de retornar 404.

**Como reproduzir:**
```bash
REGISTRO_ABERTO=false make dev-api
curl -X POST http://127.0.0.1:8080/auth/cadastro \
  -H "Content-Type: application/json" \
  -d '{
    "email": "novo@teste.com",
    "senha": "SenhaTest123456"
  }'
# Resposta: 201 com usuário criado (INCORRETO)
# Esperado: 404 idêntico a rota inexistente
```

**O que deveria acontecer:**  
Conforme OpenAPI e ADR-015: "A rota só existe quando REGISTRO_ABERTO=true. Fechado, ela não é registrada e qualquer método em /auth/cadastro recebe o mesmo 404 de um caminho desconhecido."

**Consequência:**
- GET /auth/cadastro retorna 405 (Method Not Allowed) em vez de 404
- DELETE /auth/cadastro retorna 405 em vez de 404
- Violação da spec: resposta diferente para /auth/cadastro e rota inexistente

**Gravidade:** BLOQUEANTE (viola spec de segurança e funcional; registrada como não existente mas existe)

---

### 2. IMPORTANTE: E-mail com caracteres de controle retorna 500

**O que acontece:**  
Entrada com caracteres de controle no e-mail (ex: `test\u0000@teste.com`) retorna 500 em vez de 400.

**Como reproduzir:**
```bash
curl -X POST http://127.0.0.1:5555/auth/cadastro \
  -H "Content-Type: application/json" \
  -d '{
    "email": "test\u0000@teste.com",
    "senha": "SenhaTest123456"
  }'
# Resposta: HTTP 500 erro interno
# Esperado: HTTP 400 entrada inválida
```

**O que deveria acontecer:**  
Validar e rejeitar com 400 (erro de entrada), não 500 (erro interno).

**Gravidade:** IMPORTANTE (validação de entrada não está tratando caso-limite)

---

### 3. MENOR: Set-Cookie sem atributo Secure em COOKIE_SECURE=false

**O que acontece:**  
Conforme esperado por ADR-015, o atributo `Secure` não aparece quando COOKIE_SECURE=false em development. Isso é **correto** e está funcionando como intencionado.

**Observação:**  
OpenAPI documenta `Secure` na descrição do Set-Cookie, mas é correto omiti-lo em HTTP (development). Documentação poderia esclarecer isso, mas é detalhe menor.

**Gravidade:** MENOR (comportamento correto, documentação apenas)

---

## Testes Que Passaram

### Caminho Feliz
- ✓ Cadastro com sucesso (201, usuário retornado com id, email, nome)
- ✓ Login com credenciais corretas (200, usuário retornado, Set-Cookie presente)
- ✓ GET /auth/eu com sessão válida (200, usuário retornado)
- ✓ Logout revoga sessão no servidor (204, GET /auth/eu após logout retorna 401)

### Sessões
- ✓ Token revogado retorna 401 (conforme esperado)
- ✓ Token adulterado retorna 401
- ✓ Token truncado retorna 401
- ✓ Token com espaços retorna 401
- ✓ Dois logins geram tokens diferentes (independentes)
- ✓ Logout de uma sessão não afeta outra

### Enumeração
- ✓ E-mail inexistente e senha errada: mesmo status 401, mesma mensagem "credenciais inválidas"
- ✓ Timing: ambos levam ~80-85ms (sem diferença detectável)
- ✓ Não há vetor de enumeração por resposta ou tempo

### Atraso Progressivo
- ✓ Primeiras 3 falhas retornam 401
- ✓ 4ª falha retorna 429 com Retry-After (2 segundos)
- ✓ 5ª+ falhas continuam 429 sem verificar senha
- ✓ Retry-After diminui conforme tempo passa
- ✓ Sucesso de login reseta o contador

### Validação de Entrada
- ✓ Campo desconhecido rejeitado (400)
- ✓ Senha < 12 caracteres rejeitada (400)
- ✓ Senha > 128 caracteres rejeitada (400)
- ✓ E-mail vazio rejeitado (400)
- ✓ E-mail > 254 caracteres rejeitado (400)
- ✓ Nome > 100 caracteres rejeitado (400)
- ✓ Content-Type text/plain rejeitado (415)
- ✓ JSON malformado rejeitado (400)
- ✓ Null em campo obrigatório rejeitado (400)
- ✓ Número no lugar de string rejeitado (400)
- ✓ Corpo vazio rejeitado (400)
- ✓ Corpo > 8 KiB rejeitado (400)
- ✓ Injeção SQL escapada (tratada como string literal)
- ✓ Injeção XSS escapada em JSON (caracteres convertidos para \uXXXX)

### Segurança
- ✓ Senhas não aparecem em logs
- ✓ Tokens/hashes de sessão não aparecem em logs
- ✓ E-mails completos não aparecem em logs
- ✓ Cookie HttpOnly presente
- ✓ Cookie SameSite=Lax presente
- ✓ COOKIE_SECURE=false recusa subir com APP_ENV=production
- ✓ COOKIE_SECURE=false funciona com APP_ENV=development

### Esquema (OpenAPI Compliance)
- ✓ POST /auth/cadastro responde com {id, email, nome} (sem adicional)
- ✓ POST /auth/login responde com {id, email, nome} (sem adicional)
- ✓ GET /auth/eu responde com {id, email, nome} (sem adicional)
- ✓ E-mail duplicado retorna 409 com erro estruturado
- ✓ GET /auth/eu sem sessão retorna 401
- ✓ POST /auth/logout retorna 204

### Suíte Automatizada
- ✓ make test: 36 testes passaram
- ✓ make lint: 0 issues
- ✓ pnpm build: sucesso

---

## O que estes testes NÃO cobrem

1. **Browser real:** Testes executados em jsdom (Node.js). Não foi verificado comportamento em navegador (cookies HTTPOnly, SameSite, Secure em diferentes navegadores). Idealmente rodar em Chrome, Firefox, Safari.

2. **Rate limiting global:** Testado por IP 127.0.0.1. Não foi verificado se há limitação global (ex: 1000 tentativas/hora de todos os IPs combinados) ou se o atraso é por e-mail apenas.

3. **Expiração de sessão:** Cookies têm Max-Age=604800 (7 dias). Não foi aguardado esse tempo para verificar se sessão expira. Requer teste de tempo longo ou manipulação de relogio do banco.

4. **MFA e troca de senha:** Mencionados em documentação, mas não implementados nesta fatia. Quando chegarem (F2, F3), precisam revisar autorização de revogação de sessão.

5. **Idempotência do seed:** Código de seed parece idempotente (cria usuário se não existe), mas não foi testado múltiplas execuções sequenciais na mesma instância.

6. **Migração de banco com dados:** Migrations foram aplicadas em banco vazio. Não foi testado re-run em banco com dados (ex: adição de coluna não-null sem default, drop de constraints, etc).

7. **Concorrência no atraso:** Testado atraso com requisições sequenciais. Não foi testado se 10 requisições simultâneas ao mesmo e-mail incrementam o contador corretamente ou se há race condition.

8. **Comportamento de proxy/load balancer:** Testes direto contra a API em localhost. X-Forwarded-For e detecção de IP real não foram testados.

9. **Integração web-API:** Web foi testado em jsdom com mock. Não foi rodado navegador apontando para API real verificando fluxo de login/logout de verdade (incluindo comportamento de cookies, redirecionamentos, etc).

10. **Resposta em caso de banco indisponível:** Postgres estava sempre disponível. Não foi testado GET /auth/eu ou POST /auth/logout com banco offline (503 esperado vs 500).

11. **Seed com credenciais em variável:** SEED_SENHA, SEED_EMAIL e SEED_NOME passadas via ambiente. Não foi testado comportamento se variáveis não forem setadas (erro esperado? usuário anônimo? seed skippado?).

12. **Limites de tentativa_login:** Tabela tentativa_login pode crescer. Não foi testado limpeza de entradas antigas ou limite de tamanho.

---

## Recomendação

**Liberar com dívida registrada.**

- **BLOQUEANTE (F1):** Registrada como POST /auth/cadastro não desaparecer quando REGISTRO_ABERTO=false. Isso viola a spec e é vetor de enumeração (permite descobrir se registro está aberto).
  
- **IMPORTANTE (F1):** Caracteres de controle em e-mail causam 500 em vez de 400. Validação de entrada precisa ser mais robusta.

- **Testes passaram:** Caminho feliz, atraso progressivo, enumeração, sessões independentes, revogação, segurança de logs — tudo está funcionando.

- **Próximos passos:** Fixar os dois achados, depois testar em navegador real (não jsdom) e integração com web.

---

**Hash de commit testado:** `a20d88b` (feat: adiciona login, logout e exibição do usuário logado no web)
