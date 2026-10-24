# Runbook de produção

Como subir, operar e recuperar o Ballast em produção (ADR-044). Os passos
marcados **[dono]** pedem conta, clique em painel ou segredo: quem faz é o
dono.

```
navegador ──HTTPS──> Vercel (front + /api/*)
                        │ x-origin-secret, x-real-ip
                        ▼
                   VM Oracle (São Paulo, arm64)
                   Caddy :443 (TLS do DuckDNS) ──> api:8080 (Docker)
                                                      │ TLS
                                                      ▼
                                                 Neon (aws-sa-east-1)
GitHub Actions: deploy (manual), backup (semanal) e saude (de hora em hora)
backup ──pg_dump cifrado──> Object Storage do Oracle
```

## 1. Contas [dono]

- **Oracle Cloud.** A região de origem (home region) é escolhida no cadastro e
  não muda depois. Recurso Always Free só existe nela. Escolha
  **Brazil East (São Paulo)**.
- **Neon**, **Vercel** e **DuckDNS** com o login que preferir.

## 2. Neon [dono]

1. Crie o projeto com **Postgres 17** na região **AWS São Paulo
   (aws-sa-east-1)**. O banco padrão e o dono `neondb_owner` vêm junto. Esse
   dono é o `DB_MIGRATION_USER`.
2. Copie o host do **endpoint direto**, o que não tem `-pooler` no nome. O
   `pg_dump` e o Flyway precisam da conexão direta.
3. **Crie o papel da aplicação por SQL, nunca pelo Console.** Papel criado pelo
   Console ou pela API entra no `neon_superuser` (`CREATEROLE`, `BYPASSRLS`,
   `pg_write_all_data`), e isso desfaz o ADR-021. No seu computador, conectado
   como `neondb_owner`:

   ```
   psql "postgresql://neondb_owner@<host-direto>/<banco>?sslmode=verify-full&sslrootcert=system"
   CREATE ROLE ballast_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE;
   \password ballast_app
   ```

   O `\password` pede a senha sem deixar no histórico. Confira:

   ```
   SELECT pg_has_role('ballast_app', 'neon_superuser', 'member');  -- tem de dar f
   ```

4. Os `GRANT` vêm da V3 na primeira migração (passo 8).

## 3. VM no Oracle [dono]

1. Crie a instância **VM.Standard.A1.Flex**, 2 OCPU e 12 GB, com Ubuntu 24.04
   aarch64. Use só chave SSH, sem senha.
2. Na security list da VCN, libere **TCP 80 e 443** de `0.0.0.0/0`. A porta 22
   continua aberta para o deploy, só com chave.
3. Instale o Docker Engine com o plugin compose (repositório oficial do Docker
   para Ubuntu) e ligue as atualizações automáticas de segurança
   (`unattended-upgrades`).
4. Crie o usuário de deploy e a pasta:

   ```
   sudo adduser --disabled-password deploy
   sudo usermod -aG docker deploy      # o grupo docker vale como root na VM
   sudo install -d -o deploy -g deploy -m 750 /opt/ballast
   ```

5. Gere um par de chaves só para o deploy (`ssh-keygen -t ed25519 -C deploy`).
   A pública vai em `/home/deploy/.ssh/authorized_keys`, e a privada vai para o
   segredo `VM_SSH_KEY` (passo 7).
6. Em `/opt/ballast`, crie `api.env` e `caddy.env` a partir de
   `deploy/api.env.example` e `deploy/caddy.env.example`, com `chmod 600`.
7. A imagem do Ubuntu no Oracle vem com regras de `iptables` próprias. Se o
   teste de fora do passo 10 não chegar na 443, confira o `iptables -L` e
   persista a liberação com `netfilter-persistent`.

## 4. DuckDNS [dono]

1. Crie o subdomínio apontando para o IP público da VM.
2. Troque `trocar-pelo-subdominio.duckdns.org` em `web/vercel.json` pelo
   subdomínio, por PR. O deploy recusa o arquivo enquanto o texto antigo
   estiver lá.
3. Ponha o mesmo host em `API_HOST`, no `caddy.env`.

## 5. Vercel [dono]

1. Importe o repositório. Configure:
   - Root Directory `web`;
   - framework Vite;
   - build `pnpm build`, saída `dist`.
2. Deixe ligado "Include files outside the Root Directory": o build gera os
   tipos a partir de `../api/openapi.yaml`.
3. **Production Branch: `producao`.** O deploy move essa branch só depois da
   API nova estar no ar.
4. Node: o Vercel vai até 24 e o CI usa 26. Ponha `ENABLE_EXPERIMENTAL_COREPACK=1`
   para o pnpm do `packageManager` e confira o primeiro build.
5. Variável `ORIGIN_SECRET` (`openssl rand -hex 32`) **só no ambiente
   Production**. O mesmo valor vai no `caddy.env`. Fora do Production, o
   `/api` dos previews leva 403 da VM, que é o que se quer.
6. Não defina `VITE_API_URL`. Se ela apontar para outra origem, o build falha
   de propósito (ADR-044).
7. **Firewall, opcional e recomendado.** A mitigação de DDoS do Vercel já vale
   no Hobby, sem configurar nada. Em Firewall, crie uma regra de rate limit em
   `/api/`, por IP, com ação 429. Por exemplo, 300 por minuto, abaixo dos 600
   da API (ADR-049). Requisição barrada no Vercel nem acorda o Neon. Confira
   no painel quantas regras o seu plano permite.

## 6. Object Storage [dono]

1. Crie o bucket privado `ballast-backup`.
2. Crie uma regra de ciclo de vida para apagar objeto com mais de 90 dias. Os
   20 GB do Always Free sobram.
3. Crie um **pre-authenticated request no bucket, só "Permit object writes"**,
   com validade de 1 ano. Ponha um lembrete para trocar. A URL termina em
   `/o/` e vai no segredo `OCI_BACKUP_PAR_URL`.
4. Gere `BACKUP_PASSPHRASE` (`openssl rand -base64 48`) e guarde uma cópia
   fora do GitHub, num gerenciador de senhas. **Sem ela nenhum backup abre.**

## 7. GitHub [dono]

**Ambiente `producao`.** Ponha você como revisor obrigatório: cada job do deploy
espera sua aprovação. Segredos:

| Segredo | Valor |
|---|---|
| `DATABASE_URL` | `jdbc:postgresql://<host-direto>/<banco>?sslmode=verify-full&sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory` |
| `DB_MIGRATION_USER` / `DB_MIGRATION_PASSWORD` | `neondb_owner` e a senha dele |
| `DB_USER` | `ballast_app` (só o nome) |
| `VM_HOST` / `VM_USER` | IP ou subdomínio da VM, e `deploy` |
| `VM_SSH_KEY` | a chave privada do passo 3 |
| `VM_KNOWN_HOSTS` | saída de `ssh-keyscan -t ed25519 <VM_HOST>`, conferida contra a impressão digital que o console do Oracle mostra |

**Ambiente `backup`.** Sem revisor, porque o cron não teria quem aprovasse.
Segredos:

| Segredo | Valor |
|---|---|
| `PGHOST` / `PGDATABASE` | host direto e nome do banco |
| `PGUSER` / `PGPASSWORD` | `neondb_owner` e a senha dele |
| `BACKUP_PASSPHRASE` | passo 6 |
| `OCI_BACKUP_PAR_URL` | passo 6 |

**Variável do repositório** `PRODUCAO_URL`: `https://<projeto>.vercel.app`, sem
barra no fim.

## 8. Primeiro deploy

1. Na `main` com o CI verde: Actions, `deploy`, Run workflow. Aprove os jobs.
2. O primeiro `migracao` aplica da V1 à V19 e roda os `GRANT` para
   `ballast_app`.
3. Crie o primeiro usuário. O cadastro fica fechado. Para abrir depois, ponha
   `REGISTRO_ABERTO=true` no `api.env` e o build do web com
   `VITE_REGISTRO_ABERTO=true`. O teto é `CADASTRO_MAX_USUARIOS` (padrão 20),
   e cada IP cadastra no máximo 5 por hora (ADR-049). Na VM, como `deploy`:

   ```
   cd /opt/ballast
   read -r SEED_EMAIL; read -rs SEED_SENHA; export SEED_EMAIL SEED_SENHA
   API_TAG="$(cat .api_tag)" docker compose run --rm \
     -e SPRING_PROFILES_ACTIVE=producao,seed -e SEED_EMAIL -e SEED_SENHA \
     -e BALLAST_RECURRENCE_JOB_ENABLED=false -e BALLAST_INDICES_JOB_ENABLED=false \
     -e BALLAST_COTACOES_JOB_ENABLED=false -e BALLAST_IDENTITY_CLEANUP_JOB_ENABLED=false api
   unset SEED_SENHA
   ```

## 9. Riscos que ficam com o dono

- **A Oracle recupera VM ociosa.** A regra: em 7 dias, CPU no percentil 95
  abaixo de 20%, rede abaixo de 20% e, no A1, memória abaixo de 20%. Um app de
  uma pessoa fica abaixo dos três, e o cron do `/healthz` não muda isso: ele
  só avisa que caiu. Saídas conhecidas:
  - converter a conta para Pay As You Go (o Always Free continua de graça;
    relatos da comunidade dizem que conta paga não perde instância ociosa, mas
    a página do Oracle não diz);
  - manter um backup que permita refazer tudo, que é este runbook mais o
    `backup`.
- **RPO.** Backup semanal mais as 6 horas de histórico do Neon: perder o
  projeto do Neon custa até 7 dias. O `docs/ameacas.md` fala em 24 h. Para
  diário, troque o cron do `backup.yml` para `'0 6 * * *'`.
- **Workflow agendado para em repositório público sem commit há 60 dias.** Se
  o projeto ficar parado, abra Actions e reative `backup` e `saude`, ou faça
  um commit.
- **Cobrança.** Neon Free, Vercel Hobby e Oracle Always Free não cobram
  excesso: a cota acaba e o serviço para até o mês seguinte. Um ataque tira o
  app do ar, sem gerar fatura. Isso deixa de valer com cartão num plano pago
  ou com a conta do Oracle em Pay As You Go. Nesse caso, ponha um alerta de
  orçamento antes.
- **Cota do Neon.** Confira no painel, depois do primeiro dia, que o compute
  dorme. Se ele ficar acordado com o pool vazio, a conta do ADR-044 não fecha.

## 10. Conferência depois do deploy

- `curl -fsS $PRODUCAO_URL/api/healthz` responde 200.
- `curl -s -o /dev/null -w '%{http_code}' https://<subdominio>.duckdns.org/healthz`
  responde **403**: ninguém fala com a API pulando o Vercel.
- O login no PWA funciona. O cookie `ballast_sessao` sai `HttpOnly`, `Secure`,
  `SameSite=Lax`, no domínio do Vercel.
- Uma consulta com parâmetro (Lançamentos de outro mês) volta certa: confere
  que o Vercel repassa a query string.
- No log da API (`docker compose logs api`), uma senha errada aparece com o
  seu IP, não com o do Caddy.
- Rode o `backup` à mão uma vez e faça a restauração do passo 11.

## 11. Restaurar um backup

1. Baixe o objeto do bucket pelo console do Oracle.
2. Decifre:

   ```
   gpg --decrypt ballast-AAAAMMDDTHHMMSSZ.dump.gpg > ballast.dump
   ```

3. Teste num Postgres 17 descartável (`docker run postgres:17`) ou numa branch
   nova do Neon:

   ```
   pg_restore --no-owner --dbname "<url-do-destino>" ballast.dump
   ```

4. Para voltar produção: restaure numa branch do Neon, confira, troque o host
   em `DATABASE_URL` (VM e GitHub) e rode o deploy.
5. Anote a data do teste na linha 20 de `docs/seguranca.md`.

## 12. Voltar uma versão

- **API.** As imagens antigas ficam carregadas na VM:

  ```
  cd /opt/ballast && echo <tag-antiga> > .api_tag && API_TAG=<tag-antiga> docker compose up -d
  ```

  Migration não volta. Só dá para voltar a API se a migration nova for
  compatível com o código anterior.
- **Front.** No Vercel, "Promote to Production" do deploy anterior.
- **Limpeza.** Uma vez por mês, `docker image prune -a --filter until=720h`.

## 13. Trocar segredos

- **`ORIGIN_SECRET`.** Troque no `caddy.env` e no Vercel, rode `docker compose
  up -d` e faça redeploy no Vercel. No intervalo entre os dois, a API responde
  403.
- **Senha do banco.** Troque no Neon e depois no `api.env` (`ballast_app`) ou
  nos segredos do GitHub (`neondb_owner`).
- **PAR do backup.** Crie o novo, troque o segredo e apague o antigo.
