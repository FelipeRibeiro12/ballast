# ADR-044: Produção em tier gratuito

**Status:** aceito
**Data:** 2026-10-23

## Contexto

A hospedagem estava em "A completar". O dono decidiu tudo em tier gratuito:

- **API:** VM ARM do Oracle Cloud Always Free (2 OCPU, 12 GB) em São Paulo.
- **Banco:** Postgres no Neon Free.
- **Front:** Vercel.
- **TLS:** Caddy, com subdomínio do DuckDNS.

Pediu também cinco coisas:
- front e API na mesma origem, com rewrite de `/api/*` no Vercel e um teste;
- jar compilado no amd64 e imagem arm64 montada sem QEMU;
- Flyway fora da subida da aplicação, como passo de deploy com a credencial de dono;
- `pg_dump` semanal para o Object Storage do Oracle;
- cron no `/healthz`.

## Decisão

### Mesma origem

- O front de produção chama a API em `/api` (`PREFIXO_DA_API`). O Vercel repassa
  `/api/(.*)` para `https://<subdominio>.duckdns.org/$1`. O cookie
  `SameSite=Lax` do ADR-020 continua valendo, sem CORS.
- O `web/vercel.json` usa `routes` e não `rewrites`. A diferença é um
  `transform` que põe o cabeçalho `x-origin-secret` (variável `ORIGIN_SECRET`
  do projeto) em toda requisição repassada. O Vercel não aceita `routes`
  junto com `rewrites`.
- O Caddy da VM só repassa à API a requisição com o segredo. O resto leva 403,
  então ninguém fala com a API pulando o Vercel.
- O teste `web/src/producao.test.ts` quebra se a rota de `/api` sumir, se o
  destino não for HTTPS, se ela ficar depois dos arquivos estáticos ou se o
  segredo sair.
- O `vite.config.ts` derruba o build de produção se `VITE_API_URL` apontar
  para outra origem. Isso vale também para variável posta no painel do Vercel.
- O `vite preview` do e2e tira o prefixo como o Vercel, então o e2e passa pelo
  mesmo caminho de produção.

### IP do visitante

O achado B3 da auditoria da F1 fecha assim:
- O Vercel manda o IP do visitante em `x-real-ip`.
- O Caddy, só na requisição com o segredo, copia esse valor para o
  `X-Forwarded-For`, trocando o que o cliente tiver mandado.
- O perfil `producao` liga `server.forward-headers-strategy: native`. O Tomcat
  só aceita o cabeçalho vindo de endereço interno, que é a rede do compose.
- O log de login passa a ter o IP do visitante, e não o do Caddy.

### Imagem arm64 sem QEMU

- O jar compila no runner amd64 (`mvnw package`).
- O `api/Dockerfile` parte do `eclipse-temurin:25-jre-noble`, copia o jar e
  não tem `RUN`. O buildx monta a imagem arm64 sem executar nada da
  arquitetura alvo. Usuário pelo número (10001), porque criar com nome pediria
  um `RUN`.
- A imagem vai para a VM por `docker save | ssh docker load`, sem registry e
  sem token de leitura na VM.

### Flyway como passo de deploy

- O perfil `migracao` roda o mesmo jar como dono do schema, sem servidor web,
  e sai. Ele aplica as migrations, o JPA confere o schema e os jobs ficam
  desligados.
- O passo recebe só `DATABASE_URL`, `DB_MIGRATION_USER`,
  `DB_MIGRATION_PASSWORD` e o nome em `DB_USER`, que o `GRANT` da V3 usa.
- Isso dispensa a CLI do Flyway: a versão é a mesma do app, sem dependência
  nova.
- O perfil `producao` tem `spring.flyway.enabled: false`, e a VM não recebe a
  credencial de dono. O `DeployProfilesTest` prova os dois passos contra o
  Postgres.
- O papel da aplicação no Neon é criado por SQL. Papel criado pelo Console
  ganha `neon_superuser` (`CREATEROLE`, `BYPASSRLS`, `pg_write_all_data`), o
  que desfaria o ADR-021.

### Neon Free

Os limites do Free: 100 CU-hora por mês, dorme depois de 5 minutos parado sem
poder desligar isso, e volta 6 horas no tempo. O perfil `producao` ajusta o
pool para isso:
- `connection-timeout` de 10 s. Acordar leva de centenas de milissegundos a
  alguns segundos, e com os 2 s do padrão a primeira requisição depois da
  pausa daria 503. Em produção, com o banco congelado, o `/healthz` passa a
  responder em até 10 s.
- `minimum-idle: 0`, `idle-timeout` de 30 s e pool de 5. A documentação do
  Neon não diz se conexão aberta e parada conta como uso. Com o pool vazio o
  banco pode dormir.
- Endpoint direto (sem `-pooler`), região `aws-sa-east-1`, Postgres 17, igual
  aos testes.

### Backup

- Workflow `backup` no GitHub Actions, semanal como o dono pediu:
  1. `pg_dump --format=custom` com Postgres 17 e TLS verificado;
  2. `pg_restore --list` para conferir o arquivo;
  3. cifra simétrica AES-256 do `gpg`;
  4. envio por PAR (URL pré-autenticada) só de escrita no bucket.
- Roda no Actions, e não na VM, para a credencial de dono não morar na VM. Se
  alguém tomar a VM, não lê nem apaga backup.
- Ambiente `backup`, separado do `producao`: aprovação manual no `producao`
  travaria o cron.

### Saúde

- Workflow `saude` de hora em hora em `${PRODUCAO_URL}/api/healthz`. Ele passa
  pelo Vercel, pelo Caddy, pela API e pelo Neon.
- De hora em hora, e não a cada 5 minutos: o `/healthz` toca o banco e cada
  toque acorda o Neon por 5 minutos. A cada 5 minutos o banco nunca dormiria,
  e a conta seria 744 h × 0,25 CU = 186 CU-hora, acima dos 100 do Free.
- Somando de hora em hora (15,5 CU-hora), o job da CoinGecko de 30 em 30
  minutos (31 CU-hora) e o uso real, a conta fica abaixo de 100.

### Ordem do deploy

O workflow `deploy` é manual, só a partir da `main`, e faz nesta ordem:
1. confere que o CI do commit está verde;
2. monta jar e imagem;
3. migra o banco;
4. troca a API na VM, com volta automática para a tag anterior se o
   `/healthz` não responder;
5. só então move a branch `producao`, que é a branch de produção do Vercel.

Assim o front novo nunca chama uma API velha.

## Justificativa

O segredo de origem é o que torna confiável tanto o "só pelo Vercel" quanto o
IP do visitante, e o Vercel documenta esse uso. O perfil `migracao` reaproveita
o mesmo artefato que vai para produção, então a migração testada é a que
roda.

## Alternativas descartadas

- **`rewrites` simples**: a VM aceitaria acesso direto, e o `X-Forwarded-For`
  seria forjável.
- **Flyway CLI em imagem própria**: dependência nova e uma segunda versão do
  Flyway para manter igual à do app.
- **Backup por cron na VM**: põe a credencial de dono na máquina mais exposta.
- **GHCR para a imagem**: pede um token de leitura de pacotes na VM.
- **`/healthz` a cada 5 minutos**: estoura a cota do Neon (conta acima).

## Consequência

- **O cron do `/healthz` não impede a Oracle de recuperar a VM.** A regra conta
  CPU no percentil 95 abaixo de 20%, rede abaixo de 20% e, no A1, memória
  abaixo de 20%, durante 7 dias. Um GET por hora não muda nenhum dos três. O
  cron serve para avisar quando a API cai. Evitar a recuperação fica com o
  dono (runbook, seção 9).
- **O backup semanal contraria o RPO de 24 h** da primeira versão em
  `docs/ameacas.md`. Com o Neon voltando só 6 horas, perder a conta ou o
  projeto pode custar até 7 dias de lançamentos. Para diário, basta trocar uma
  linha do cron.
- **Repositório público:**
  - o log do Actions é público, e os workflows não imprimem host, URL nem dado;
  - o GitHub desliga workflow agendado de repositório sem commit há 60 dias,
    e aí backup e saúde param calados (runbook, seção 9).
- **Node no Vercel:** o Vercel builda com Node 24 no máximo. O CI usa 26.
- **Banco exposto:** o endpoint do Neon Free é público, protegido por senha e
  TLS. A lista de IPs permitidos é de plano pago. O `docs/ameacas.md` registra
  o desvio.
