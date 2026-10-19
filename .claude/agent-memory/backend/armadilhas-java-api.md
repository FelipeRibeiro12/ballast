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
- Hibernate loga o erro do driver em `org.hibernate.orm.jdbc.error` (WARN), e o do Postgres traz `Key (email)=(...)` com o e-mail inteiro. Desligado em `application.yml`; conferir de novo se algum dado pessoal entrar em coluna unique.
- Spring Security 7: o filtro de sessão não pode ser `@Component` (o Boot o registra também como filtro de servlet). `IdentityService` e o filtro ficam em `identity`; `security` só monta a cadeia (senão há ciclo de pacote).
- `SpringApplicationBuilder.properties(...)` é prioridade mínima e perde para `${DATABASE_URL}` do application.yml; em teste passe `--chave=valor` no `run(args)`.
- `@MethodSource` do JUnit exige Stream/Collection (Map não serve). Texto com U+2028/2029 no heredoc vira caractere literal no fonte: escreva ` ` escapado.
- No fish, `env $VAR` não divide por espaço; scripts de verificação com várias variáveis vão em `bash`. No macOS, `sed -i ''`.
- OPTIONS em rota inexistente devolve `Allow` do handler estático (`/**`): comparar com a rota inexistente, não exigir ausência.
- Jackson 3 no Boot 4: `spring.jackson.deserialization.fail-on-unknown-properties`, `fail-on-trailing-tokens` e `spring.jackson.mapper.allow-coercion-of-scalars=false` funcionam em application.yml.
- Seed é o perfil `seed` (`application-seed.yml` com `web-application-type: none`) + `SeedRunner`; `make seed` = `SPRING_PROFILES_ACTIVE=seed ./mvnw spring-boot:run`.
- Rodada B da F1: `LoginAttempts` (JdbcClient, UPSERT atômico com o relógio da aplicação) e `IdentityService.authenticate` devolve `LoginResult` (Success/BadCredentials/Delayed). Testes de integração usam `TestClock` (bean `@Primary` em `IntegrationTest`, anda com o sistema até `freeze`; sempre `reset` no `@AfterEach`).
- Comentário Java com barra invertida seguida de "u" e letras vira escape unicode e quebra o compilador e o google-java-format ("illegal unicode escape"): escreva por extenso.
- `BodyLimitFilter` (`web`, `@Order(HIGHEST_PRECEDENCE)`, só `/auth/**`, 4 KiB): Content-Length alto responde 413 no filtro; chunked estoura na leitura e o `ApiExceptionHandler` acha `RequestBodyTooLargeException` na cadeia de causas. Só um Tomcat real (`RANDOM_PORT` + `HttpClient`) exercita chunked; com upload de centenas de MB o Tomcat fecha a conexão e o cliente vê reset, não o 413.
- `springdoc.api-docs.enabled` vem de `API_DOCS_ENABLED` (padrão false); `IntegrationTest` e `SecurityBaseTest` ligam por propriedade. springdoc usa `@ConditionalOnProperty` sem havingValue: valor vazio conta como ligado, então o `.env.example` traz `false` explícito e o `SecurityConfig` só libera `/v3/api-docs*` anônimo quando o valor lê como `true` (vazio vira false). Sem springdoc o path dá 404 mesmo logado.
