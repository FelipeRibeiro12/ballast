# ADR-015: sessão por token opaco guardado como SHA-256

**Status:** aceito
**Data:** 2026-10-03

## Contexto

A F1 introduz login. O servidor precisa invalidar uma sessão no logout e na troca de senha (`docs/seguranca.md`), e o cookie de sessão não pode ser lido pelo JavaScript da página.

## Decisão

- O token é opaco: 32 bytes de `crypto/rand`, codificados em base64url.
- O banco guarda só o **SHA-256** do token, na tabela `sessao`, com `expira_em` e `revogada_em`. O token em claro só existe no cookie.
- Cookie `HttpOnly`, `Secure`, `SameSite=Lax`, com o mesmo prazo da sessão.
- Logout e troca de senha marcam a sessão como revogada no servidor. Apagar o cookie não basta.
- A comparação é feita pelo hash, por busca indexada: o banco recebe o hash do token apresentado e nunca o token.

## Por que SHA-256 no token e argon2id na senha

Não é inconsistência, e ninguém deve "uniformizar" isso.

- Senha escolhida por gente tem pouca entropia. Quem vazar o banco tenta adivinhá-la offline, então o hash precisa ser caro de propósito: argon2id.
- O token é aleatório de 256 bits. Não existe lista de candidatos a tentar, e força bruta contra 2^256 não é viável com qualquer função de hash. Um hash lento aqui só gastaria CPU em toda requisição autenticada, sem ganho de segurança.
- O que o SHA-256 protege: vazamento do banco não entrega tokens utilizáveis.

## Alternativa descartada

**JWT.** Um JWT assinado é válido até expirar e não há como invalidá-lo no logout nem na troca de senha sem uma lista de revogação. Essa lista é um banco de sessão com passos a mais, ou seja, o desenho acima com mais complexidade. Sem ganho para um monolito com um banco só.

## Consequência

Cada requisição autenticada faz uma consulta indexada em `sessao`. Aceito: o app é de uso pessoal e a consulta é por índice único. Se isso virar gargalo, o remédio é cache com TTL curto, que preserva a revogação, e não troca de formato.
