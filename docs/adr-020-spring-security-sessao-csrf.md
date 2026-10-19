# ADR-020: Spring Security com filtro de sessão próprio, sem token de CSRF

**Status:** aceito
**Data:** 2026-10-19

## Contexto

A F1 volta em Java. O ADR-015 fixa o desenho da sessão (token opaco, SHA-256
no banco, cookie `HttpOnly`, `Secure`, `SameSite=Lax`) e aprovou o Spring
Security pelo `Argon2PasswordEncoder`. Falta decidir quanto do Spring Security
entra e o que fazer com o CSRF, que ele liga por padrão.

## Decisão

- Spring Security entra pela cadeia de filtros, pelo encoder e pelos
  cabeçalhos padrão. Sem `formLogin`, sem `httpBasic`, sem `HttpSession`
  (`SessionCreationPolicy.STATELESS`).
- Um filtro próprio lê o cookie, calcula o SHA-256 e busca a sessão válida em
  `sessao`. Login, cadastro e logout são controllers comuns.
- Rotas públicas listadas uma a uma. Todo o resto exige sessão.
- **O token de CSRF do Spring fica desligado.** A defesa é o `SameSite=Lax` do
  cookie mais a regra de que rota que muda estado só aceita
  `Content-Type: application/json`, e o app não habilita CORS.
- `/v3/api-docs*` só responde com `springdoc.api-docs.enabled=true`, ligado em
  desenvolvimento e teste. Em produção a spec fica no repositório.

## Justificativa

Com `Lax`, o navegador não manda o cookie em POST vindo de outro site. Um
formulário HTML de outro site também não consegue enviar `application/json`
sem preflight, e sem CORS o preflight falha. GET nunca muda estado. O token do
Spring fecharia o mesmo ataque com estado extra no PWA.

## Alternativas descartadas

- **Token de CSRF do Spring (cookie `XSRF-TOKEN` e cabeçalho)**: funciona, mas
  acrescenta estado no cliente sem cobrir ataque que as duas regras acima não
  cubram.
- **Filtro próprio sem Spring Security**: perde os cabeçalhos padrão e a
  autorização declarativa, e o ADR-015 já aprovou a dependência.

## Limitação reconhecida

A defesa depende de o PWA e a API ficarem no mesmo site. Em sites diferentes,
o `Lax` também derruba o login legítimo, e esta decisão volta junto com a
escolha da hospedagem. Qualquer rota futura que aceite outro `Content-Type`
(upload de extrato, F22) precisa de defesa própria.
