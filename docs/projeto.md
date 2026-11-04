# Ballast: resumo do projeto para uso em currículo e candidaturas

Documento escrito para ser colado em outro chat. O objetivo é dar contexto
suficiente para adaptar um CV a uma vaga. Tudo aqui sai do repositório
(`CLAUDE.md`, `docs/`, `.claude/`, `api/`, `web/`, `.github/`). Onde um número
vem de uma contagem do dia 2026-11-04, a data está dita. O que é opinião está
marcado.

## 1. O que é

Ballast é um app de controle financeiro pessoal. Junta fiat e cripto no mesmo
lugar, com recorrência, cartão de crédito parcelado, metas, orçamento e
projeção de saldo. Responde uma pergunta: quanto eu posso gastar hoje sem furar
o mês.

- Autor e dono: Felipe (um desenvolvedor, projeto solo). Começou com um
  usuário (o próprio autor), mas o modelo é multiusuário desde a primeira
  migration, com a intenção de publicar.
- Primeiro commit em 2026-10-03. Em 2026-11-04 são 353 commits em 15 dias
  distintos de trabalho, 81 ADRs, 45 arquivos de migration.
- Em produção em tier gratuito (ADR-044): VM no Oracle Cloud, Postgres no Neon,
  front no Vercel, domínio no DuckDNS, Caddy como proxy com TLS, backup no
  Object Storage.
- Não tem Open Finance, nem scraping de banco, nem custódia de cripto. O app
  observa endereço de carteira e nunca guarda chave nem assina transação.

## 2. Stack

### Backend (`api/`)

- Java 25 e Spring Boot 4, monolito modular, Maven com wrapper `mvnw`.
- Spring Web MVC, Spring Security (filtro de sessão próprio), Spring Data JPA,
  Bean Validation, JdbcClient para SQL direto de agregação.
- Postgres, com Flyway para migrations (`V1` até `V44` ou mais, nunca editadas
  depois de aplicadas).
- springdoc gera a spec OpenAPI do código; a spec é commitada em
  `api/openapi.yaml` e o cliente TypeScript é gerado dela.
- Bouncy Castle (verificação de assinatura secp256k1) e Keccak-256 escrito à
  mão para endereço EIP-55. PDFBox para ler PDF de fatura.
- Spotless para formatação (`make lint`).
- Jobs agendados com `@Scheduled`: recorrência, cotação, índices do Banco
  Central, rendimento do cofrinho, leitura de carteiras, limpeza de sessões.

### Frontend (`web/`)

- React 19 e Vite 8, TypeScript 6, PWA instalável (vite-plugin-pwa e workbox),
  mobile-first, testado em 360 px.
- Vitest com Testing Library e jsdom; Playwright para e2e.
- oxlint no lugar de ESLint (ADR-010). pnpm com lockfile commitado.
- `openapi-typescript` gera os tipos da API a partir da spec.
- Web3 só para preencher endereço: `@wagmi/core` e `viem` (EIP-6963 para EVM),
  Wallet Standard para Solana (Phantom). Carregado em chunk separado, só no
  clique.

### Infra e entrega

- GitHub Actions: `ci.yml` (spotless, `mvnw verify`, conferência da spec, testes
  e build do web, e2e), `seguranca.yml` (gitleaks, osv-scanner, `pnpm audit`,
  CodeQL), `deploy.yml`, `backup.yml`, `saude.yml`.
- Docker (Dockerfile da API, `docker-compose.yml` local, `deploy/compose.yml`
  de produção), Caddy, cloud-init, `deploy.sh`. Runbook de produção em
  `docs/runbook-producao.md` com restauração de backup e rollback.
- Usuários de banco separados: um dono do schema roda a migration, a aplicação
  só tem DML (ADR-021).

### Integrações externas

CoinGecko (cotação de cripto e USDC/BRL), Banco Central (CDI, IPCA, PTAX),
Alchemy (saldo de token em Ethereum, Base, Arbitrum, Polygon, Avalanche,
Robinhood Chain e Solana), Zerion (posição DeFi e NFT), Blockstream Esplora
(Bitcoin). Todas atrás de interfaces, com falha que não derruba o saldo.

## 3. Funcionalidades

- **Conta e sessão:** cadastro, login, sessão por token opaco guardado como
  SHA-256 em cookie `HttpOnly`/`Secure`/`SameSite`, MFA por TOTP com códigos de
  recuperação, atraso de login por IP, limite de requisições, teto de
  usuários.
- **Contas:** corrente, poupança, dinheiro, benefício (VR/VA com categorias
  permitidas), cartão, cripto, renda fixa, cofrinho. Arquivar, editar e apagar
  com confirmação.
- **Lançamentos:** receita, despesa e transferência, previsto e realizado, duas
  datas por lançamento (competência e caixa), categorias com CRUD, paginação
  por cursor e chave de idempotência.
- **Recorrência:** frequência com intervalo, dia 31 em fevereiro, divisão de
  salário em partes por dia do mês, materialização idempotente por job.
- **Cartão de crédito:** fechamento e vencimento, vencimento em dia útil
  (feriado nacional), compra parcelada com resto na última parcela, fechar a
  fatura à mão, terceiros (quem deve o quê no cartão).
- **Saldo projetado** para 90 dias e **Posso gastar** por janela escolhida,
  com o ciclo da fatura.
- **Orçamento por categoria**, **metas** com aporte, **relatórios** por
  categoria e série mensal, **patrimônio no tempo**.
- **Renda fixa:** CDB, LCI e LCA com CDI, IPCA e prefixado, marcação e
  resgate. **Cofrinho** com rendimento diário lançado como receita (percentual
  do CDI, dia útil, catch-up, também na abertura do app).
- **Cripto:** posições por quantidade (valor derivado da cotação), aporte e
  valorização, cotação automática e histórica, valor em real pelo USDC/BRL.
- **Carteiras observadas:** cadastro por endereço (digitado ou conectado),
  prova de posse por assinatura de mensagem, leitura multirrede, só token
  cotado pela CoinGecko, corte de US$ 0,50, DeFi e pools de liquidez, NFT,
  valor por carteira e total, variação de 24 h (ADR-080, 081 e 082).
- **Importação de extrato:** OFX, CSV com mapeamento e PDF de fatura, sempre
  com revisão e confirmação antes de entrar (ADR-008).
- **PWA:** instalável, rolagem própria no celular, cabeçalhos de segurança.

Próximas ideias já decididas, ainda sem código: leitura da fase do mercado
(bull, bear, acumulação) e boas posições de LP; comparador de forma de
pagamento considerando juros e rendimento do dinheiro aplicado. Backlog:
alerta de conta a vencer, previsão e anomalia em Python (ADR-007), classificação
automática de categoria, Capacitor.

## 4. Decisões de engenharia que valem citar

- **Dinheiro:** `BigDecimal` sempre, `numeric(18,2)` para fiat e
  `numeric(38,18)` para cripto. No JSON, fiat em inteiro de centavos e cripto
  em string, porque JavaScript vira decimal em `double`. Compara com
  `compareTo`, arredonda uma vez na borda de saída, `RoundingMode` explícito.
- **Duas datas:** competência para gráfico e orçamento, caixa para saldo e
  projeção. A distinção está no modelo desde a primeira migration.
- **Saldo não é coluna:** é saldo inicial mais a soma dos lançamentos
  realizados. Evita divergência e corrida entre requisições.
- **Autorização por escopo:** toda tabela tem `usuario_id` e toda query filtra
  por ele. Rota sem permissão não existe; toda rota tem teste "A pede recurso
  de B e recebe 404".
- **Concorrência:** ordem fixa de travas (`FOR UPDATE`), prazo curto de trava
  em job, idempotência por marcador com `unique`, chave de idempotência nas
  escritas.
- **Entrada não confiável:** limite de tamanho e de linhas, XXE desligado no
  OFX, fórmula neutralizada em CSV, PDF só em memória com teto de páginas e de
  tempo, nome de arquivo nunca compõe caminho.
- **Cripto sem custódia:** só endereço, prova de posse com nonce de uso único e
  verificação EIP-191/Ed25519, endereço fora de log e de URL própria.
- **Falha externa isolada:** cotação fora do ar não trava lançamento; leitura
  de carteira parcial nunca zera o que já existe.
- **Contrato gerado do código:** a spec OpenAPI vem do Java, o cliente TS vem
  da spec, e o CI falha se algum dos dois sair de sincronia.
- **Backend trocado com registro:** começou em Go (ADR-002) e migrou para Java
  e Spring Boot (ADR-016), com os ADRs de Go marcados como substituídos.

## 5. Testes e qualidade

- TDD: teste que falha pelo motivo certo, versão mais simples que passa, suíte
  verde, commit, refatoração em commit separado (skill `tdd-ciclo`).
- Integração contra Postgres real por Testcontainers; banco em memória não é
  usado. Em 2026-10-30 o diário registra 2302 testes de API e 1155 do web.
- Casos de domínio exigidos: recorrência (dia 31, bissexto, idempotência),
  divisão (3 x 33,33% somando exato), cartão (fechamento em fevereiro, compra
  no dia do fechamento), autorização A/B em toda rota, cotação fora do ar.
- Pipeline verde é quem libera o merge, não opinião sobre estilo. Só achado que
  bloqueia segura: quebra, furo de segurança, perda de dado, contrato violado,
  teste ausente.

## 6. Segurança

Tratada como requisito desde o início, não como fase final.

- Regras não negociáveis em `~/.claude/rules/seguranca.md` e
  `.claude/rules/seguranca.md`: segredo fora do código, validação na borda com
  whitelist, query parametrizada, hash lento (Argon2/derivação lenta), sessão
  com expiração e logout no servidor, comparação em tempo constante, erro
  genérico para o cliente, log sem dado sensível, menor privilégio.
- Hook `bloqueia-segredo.sh` impede escrita em `.env`, `*.key` e `*.pem`.
- Modelo de ameaças em `docs/ameacas.md` (dado sensível, perfis, superfície,
  LGPD, abusos do domínio, importação, carteira) e checklist em
  `docs/seguranca.md`.
- CI de segurança: gitleaks, osv-scanner, `pnpm audit`, CodeQL.
- Auditorias por agente `security` por fatia, com achados por gravidade; o que
  não bloqueia vira dívida com dono no `docs/backlog.md`.
- Endurecimento da borda: filtro de Origin, teto de corpo, nosniff, cabeçalhos
  do front, rate limit por IP, tetos por usuário (ADR-049 a 056).

## 7. Como foi construído: IA, SDD, TDD, agentes e skills

O projeto foi desenvolvido por uma pessoa trabalhando com o Claude Code como
parceiro de implementação, dentro de um processo escrito. Para currículo, o
ponto honesto é: o autor define produto, regras e decisões; agentes de IA
implementam, testam e auditam sob essas regras. Diga isso com essas palavras.

- **Spec-driven development (SDD):** o contexto vem de documentos antes do
  código: `docs/visao.md`, `docs/contracts/dados.md` (modelo de dados, lido
  antes de mexer em tabela), `docs/ameacas.md`, 81 ADRs e o backlog em fatias
  verticais. A spec OpenAPI é gerada do código e vira o contrato do cliente.
  É spec-driven no sentido de documento primeiro e contrato versionado; não
  usa um framework formal de SDD.
- **Decisões registradas (ADR):** cada decisão relevante vira um ADR com
  contexto, decisão e consequência. Reverter ADR aceito é decisão do dono.
- **TDD:** skill `tdd-ciclo` carregada nos agentes de backend e frontend.
- **Fatias verticais:** cada fatia atravessa banco, API, web e testes, e fecha
  com PR. `docs/diario.md` registra o que entrou, decisões, dívida, achados e
  estado do pipeline de cada fatia.
- **CLAUDE.md e regras:** `CLAUDE.md` do projeto (stack, dinheiro, autorização,
  limites de cripto, como trabalhar) mais regras por tipo de arquivo em
  `.claude/rules/` (Java/Spring, SQL, TypeScript, React, segurança,
  comentários) que carregam ao ler os arquivos correspondentes.
- **Agentes especializados** (`.claude/agents/`, cada um com ferramentas,
  modelo e memória de projeto próprios):
  - `tech-lead`: orquestra, define escopo, mantém contratos e ADRs, delega,
    commita, abre PR, conserta pipeline.
  - `backend`, `frontend`, `dba`, `devops`: implementam dentro do seu limite.
  - `qa`: escreve teste e relatório, recomenda, não corrige código de produção.
  - `reviewer` e `security`: revisam diff e auditam, reportam por gravidade,
    não corrigem.
  - `docs`: README, changelog e guias, nunca comentário de código nem ADR.
- **Skills** (`.claude/skills/`): `/piloto` (roda fatias do backlog de ponta a
  ponta), `/sdlc-brainstorm`, `/sdlc-discovery`, `/sdlc-skeleton`,
  `/sdlc-fatia`, `/sdlc-hardening` (ciclo de vida), `/bootstrap` (Go, Java,
  Nest, React), `/adr`, `/pr`, `/seguranca`, `/tdd-ciclo`.
- **Memória de agente** (`.claude/agent-memory/`): armadilhas aprendidas por
  agente, reaproveitadas nas fatias seguintes.
- **Hook** de bloqueio de segredo em `.claude/settings.json`.
- **Estilo de comentário** imposto por regra: comenta o porquê, não o quê,
  nomes em inglês, comentários em português.

## 8. Linhas de currículo que o projeto sustenta

Use só o que a vaga pedir. Cada item é verificável no repositório.

- Projetei e entreguei sozinho um app financeiro full stack em produção (Java
  25, Spring Boot 4, Postgres, React 19, PWA), com 81 decisões de arquitetura
  documentadas em ADR.
- Modelei dinheiro com `BigDecimal`, centavos inteiros no JSON e duas datas
  (competência e caixa), cobrindo parcelamento com resto, recorrência em fim de
  mês e idempotência.
- Implementei autenticação com sessão opaca, MFA TOTP, rate limit e isolamento
  por usuário, validado por teste de acesso cruzado em toda rota.
- Integrei APIs externas (CoinGecko, Alchemy, Zerion, Banco Central) com
  degradação segura e sem custódia de chave, incluindo prova de posse de
  carteira por assinatura.
- Montei pipeline de CI/CD com testes de integração em Testcontainers, spec
  OpenAPI como contrato verificado, varredura de segredo e dependência, CodeQL,
  deploy em VM com Docker e Caddy, backup e runbook.
- Conduzi desenvolvimento com agentes de IA (Claude Code): agentes
  especializados, skills, regras por arquivo, memória de projeto, TDD e
  auditorias de segurança por fatia.

## 9. Mapa por tecnologia (para casar com requisitos de vaga)

| Se a vaga pede | Onde está no projeto |
|---|---|
| Java, Spring Boot | todo o `api/`, pacotes por domínio |
| API REST, OpenAPI | springdoc, `api/openapi.yaml`, cliente TS gerado |
| Postgres, SQL, modelagem | `docs/contracts/dados.md`, Flyway, SQL de agregação |
| React, TypeScript | `web/src`, PWA, Vitest |
| Segurança de aplicação | sessão, MFA, CSRF por SameSite, rate limit, importação |
| Testes automatizados | Testcontainers, Vitest, Playwright |
| CI/CD, GitHub Actions | `.github/workflows/` |
| Docker, Linux, cloud | `deploy/`, Oracle Cloud, Caddy, Neon, Vercel |
| Web3, blockchain (leitura) | EIP-55, EIP-191, EIP-6963, Ed25519, Alchemy, Zerion |
| Integração de APIs de terceiros | CoinGecko, BCB, Alchemy, Zerion, Esplora |
| Arquitetura, monolito modular | ADR-001, pacotes por domínio |
| Concorrência, jobs agendados | travas `FOR UPDATE`, `@Scheduled`, idempotência |
| IA aplicada ao desenvolvimento | seção 7 |
| Documentação técnica | ADRs, runbook, modelo de ameaças |

## 10. O que não afirmar

- Não é multiusuário em escala: foi desenhado para isso, mas o uso real é de
  uma pessoa.
- Não há SLA, equipe nem tráfego de produção para citar como métrica.
- Cripto é leitura: não há swap, assinatura de transação nem custódia.
- Previsão em Python (F27), alertas (F21), classificação automática (F28) e
  Capacitor (F29) ainda não existem.
- A IA escreveu a maior parte do código sob regras e revisão do autor. Não
  apresente o projeto como escrito à mão.
