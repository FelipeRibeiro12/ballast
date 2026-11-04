---
name: armadilhas-mfa-identity
description: Onde ficam MFA, atraso de login e porta de ação sensível no pacote identity, e pegadinhas de teste
metadata:
  type: project
---

- `MfaGate` (público) é a porta para outras fatias pedirem código em ação sensível; `MfaError` é público e tratado no `ApiExceptionHandler`, não no controller.
- `tentativa_login` tem chave `(email, ip)` desde a V21; o IP vem de `ClientKey.of(RemoteAddr)` (IPv6 pelo /64), nunca de cabeçalho.
- Teste de TOTP congela o relógio no começo de um passo de 30 s; avance 30 s antes de reusar o código depois da ativação.
- Shell do agente é fish e o sed é BSD: use python para editar arquivos e não encadeie com `&&` depois de sed.
- `make spec` regenera openapi.yaml e web/src/api/schema.d.ts; OpenApiSpecTest falha se o yaml ficar velho.
