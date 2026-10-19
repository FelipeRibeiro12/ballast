---
name: armadilhas-java-api
description: Armadilhas encontradas ao montar a api Java (Boot 4.1, springdoc 3, spotless, Hikari); consulte antes de mexer no build ou no teste de indisponibilidade do banco
metadata:
  type: project
---

- springdoc 2.x é para Boot 3; Boot 4 exige springdoc 3.x (3.1.1 usado). Spec sai em OpenAPI 3.1; `servers` precisa de bean fixo (`/`) senão varia com a requisição.
- google-java-format 1.37.0 quebra no spotless-maven-plugin 3.10.4 com Java 25 (InvocationTargetException); 1.35.0 funciona.
- Teste de 503 do /healthz: se o banco cai e a conexão do pool já está em uso, o erro é EOF imediato e o `connection-timeout` não é exercitado. Precisa esperar >500 ms (validação do Hikari) antes da requisição medida.
- Nunca usar `git checkout <arquivo>` para desfazer experimento em arquivo com mudança não commitada: apaga o trabalho junto.
- O 1º start do contexto de teste leva ~35 s (Testcontainers); `TestcontainersConfiguration` é public para os subpacotes.
