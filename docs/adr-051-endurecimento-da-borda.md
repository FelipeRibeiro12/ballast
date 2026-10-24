# ADR-051: Endurecimento da borda da API

**Status:** aceito
**Data:** 2026-10-24

## Contexto

Uma revisão da borda achou sete pontos em que a API aceitava ou respondia mais
do que devia: cookie sem prefixo, respostas sem `nosniff`, página HTML de erro
do Tomcat, JSON com chave repetida, query desconhecida ignorada, caracteres
invisíveis em nomes e e-mails, e `DELETE` sem corpo protegido só pelo
`SameSite`. O Makefile ainda passava o `.env` inteiro a todos os alvos. Cada
ponto é pequeno. Este ADR junta as decisões para ficarem num lugar só.

Nenhuma troca de ADR aceito. O ADR-020 continua valendo: sem token de CSRF, com
`SameSite=Lax`. A conferência de `Origin` é uma segunda camada.

## Decisão

### Cookie com prefixo `__Host-`

- Com `COOKIE_SECURE` ligado (o padrão), os cookies se chamam
  `__Host-ballast_sessao` e `__Host-ballast_mfa`. O navegador só aceita o
  prefixo com `Secure`, `Path=/` e sem `Domain`, que é o que a API já mandava.
  Um subdomínio ou um HTTP interceptado não consegue sobrescrevê-lo.
- Com `COOKIE_SECURE=false` (só aceito com `APP_ENV=development`), o nome fica
  `ballast_sessao` e `ballast_mfa`. O navegador descarta `__Host-` sem `Secure`,
  e o login em HTTP deixaria de funcionar. O nome depende da flag por isso.
- Quem tinha sessão aberta com o nome antigo precisa entrar de novo (uma vez, o
  cookie velho é ignorado). O front não usa o nome: o cookie é `HttpOnly`.
- O leitor de cookie continua tratando cookie repetido como sem sessão.

### `X-Content-Type-Options: nosniff` em toda resposta

O Spring Security já manda o cabeçalho, mas só nas respostas que passam pela
cadeia dele. O 429 do limite por IP e o 413 do corpo grande saem antes. Um
filtro novo (`NosniffFilter`) roda à frente de todos. As respostas do Tomcat
(abaixo) levam o cabeçalho pela válvula.

### Caminho recusado pelo Tomcat responde JSON

`%2F`, `{}` e `|` no caminho são recusados pelo Tomcat antes do Spring, com a
página HTML dele. Uma `ErrorReportValve` própria (`JsonErrorReportValve`),
registrada no host pelo `TomcatErrorConfig`, troca a página por
`{"erro":"requisição inválida"}` (o texto de `ApiExceptionHandler.messageFor`).
Sem versão, caminho nem texto do Tomcat no corpo. Só um servidor real exercita
isso, então o teste usa `RANDOM_PORT` e socket cru.

### Chave repetida no JSON vira 400

`spring.jackson.read.strict-duplicate-detection: true`. Com a última chave
valendo, um proxy ou WAF que lê a primeira enxerga outro corpo.

### Query desconhecida vira 400

Um `HandlerInterceptor` central (`UnknownQueryParameterInterceptor`) compara os
nomes da query com os `@RequestParam` do método do handler e responde 400 se
sobrar algum. Vale para toda rota do app, não só listagem: rota sem parâmetro
declarado recusa qualquer query. Comparação exata, então `?contaid=` falha. Não
há tabela paralela de nomes para esquecer de atualizar.

- Handlers de fora do pacote `com.felipe.ballast` ficam de fora (springdoc).
- O proxy do Vite não acrescenta parâmetro.
- O teste `anUnknownParameterIsIgnoredLikeInTheOtherListings` da projeção
  afirmava o comportamento antigo e passou a esperar 400.

### Caracteres Cf, NFKC e o "İ"

- **Cf (formato) é recusado com 400** em e-mail, nome de usuário, nome de conta
  e nos demais textos livres, porque o validador `@NoControlCharacters` é o
  mesmo em todos. U+200B e U+FEFF são invisíveis, U+202E inverte a exibição, e
  os tags U+E0000 a U+E007F escondem texto.
- **Exceção nos nomes: ZWJ (U+200D) e ZWNJ (U+200C).** Sem eles o emoji de
  sequência (família, profissões) quebra em pedaços e a grafia persa fica
  errada. O e-mail não tem exceção.
- **E-mail passa por NFKC antes da minúscula.** A forma de largura total e as
  ligaturas viram o texto comum, então `ＡＢ@x.com` e `ab@x.com` são a mesma
  conta. A normalização roda no construtor dos DTOs de cadastro e de login.
- **"İ" (U+0130) é recusado no e-mail.** O Java devolve "i" mais U+0307 e o
  Postgres devolve "i": o mesmo texto viraria duas identidades conforme quem
  normaliza, e o `CHECK (email = lower(email))` mede com a régua do Postgres. A
  regra geral é: se a minúscula completa do Java difere da minúscula por code
  point, o e-mail fica sem minúscula e o `BasicEmail` recusa. O teste confere
  no Postgres do Testcontainers que os dois de fato divergem.

### `Origin` nas rotas que mudam estado

Um filtro (`OriginFilter`) roda antes da segurança em POST, PUT, PATCH e DELETE,
inclusive login e logout.

- `Origin` presente: passa se estiver em `ORIGENS_PERMITIDAS` (lista separada
  por vírgula, entradas no formato `https://dominio`, sem barra) ou se a
  autoridade for a do cabeçalho `Host` do pedido. O esquema do `Host` fica de
  fora da conta porque atrás do Caddy a API vê HTTP. `null`, vazio e malformado
  dão 403 `{"erro":"origem não permitida"}`.
- `Origin` ausente: passa, salvo se `Sec-Fetch-Site` for `cross-site`. Curl e
  testes não mandam nenhum dos dois.
- Não havia configuração de origens, porque o app não habilita CORS. A lista
  vazia vale só o próprio Host, o que cobre o Vite em dev (o proxy repassa o
  `Host` original).
- **Produção precisa da variável.** O Vercel repassa `/api` para a VM: o
  `Origin` é o domínio do app e o `Host` é o da VM. Sem
  `ORIGENS_PERMITIDAS=https://<dominio-do-app>` no `api.env`, toda escrita do
  PWA dá 403.

### Makefile

Só `dev`, `dev-api`, `dev-web` e `seed` leem o `.env`, por um `ifneq` sobre
`MAKECMDGOALS`. `make test`, `make lint` e `make setup` não recebem a senha do
banco nem a chave do MFA no ambiente. O `make dev` se chama de novo com
`dev-api` e `dev-web`, por isso eles estão na lista.

## Testes

`HostCookiePrefixTest`, `InsecureCookieTest`, `NosniffTest`,
`RawRequestErrorTest`, `DuplicateKeyTest`, `UnknownQueryParameterTest`,
`NoControlCharactersTest`, `EmailNormalizationTest`, `OriginCheckTest`,
`OriginPropertiesTest` e `MakefileEnvTest`.

## Consequências

- Todos os usuários entram de novo uma vez, pela troca do nome do cookie.
- `ORIGENS_PERMITIDAS` entra no `deploy/api.env.example` e no runbook antes do
  primeiro deploy.
- Cliente que mandava parâmetro de query sobrando passa a levar 400.
- Rota nova com `@RequestParam` já entra na regra sem mexer em nada.
