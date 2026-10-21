# Ferramental — como o Claude Code está configurado neste projeto

Documenta por que a pasta `.claude/` existe e o que vem de onde. Se outra
pessoa clonar o repositório, é aqui que ela entende o setup.

## Duas metades

A configuração vive em dois lugares, e saber qual é qual evita duplicata:

| Metade | Onde | No Git? | O que tem |
|---|---|---|---|
| **Pessoal** | `~/.claude/` | não | regras de estilo e segurança, skills gerais |
| **Do projeto** | `.claude/` + `CLAUDE.md` | **sim** | agentes, skills, regras e stacks do Ballast |

A metade pessoal vale em todos os projetos da máquina e é instalada uma vez.
A metade do projeto está versionada e vem com o repositório. Os agentes e as
skills do kit também estão versionados aqui, para quem clonar ter o mesmo
fluxo sem instalar nada.

## O que está no repositório

```
CLAUDE.md                         # lei do projeto, toda sessão
.claude/
├── agents/                       # tech-lead e especialistas
├── skills/                       # /piloto, /bootstrap, /pr, /adr...
├── stacks/                       # referência por stack; a ativa vira rule
├── rules/
│   ├── java-spring.md            # carrega ao ler api/**/*.java
│   ├── sql.md                    # carrega ao ler api/**/*.sql
│   ├── typescript.md             # carrega ao ler web/**/*.ts(x)
│   ├── react.md                  # carrega ao ler web/**/*.tsx
│   └── comentarios.md, seguranca.md, solidity.md
├── hooks/bloqueia-segredo.sh     # bloqueia escrita em .env, *.key, *.pem
└── settings.json                 # registra o hook
.github/workflows/ci.yml          # spotless, mvnw verify, spec, web, e2e
.github/workflows/seguranca.yml   # gitleaks, osv-scanner, pnpm audit, CodeQL
docs/                             # visão, ADRs, modelo, backlog, ameaças
```

## O que precisa estar em `~/.claude/`

Vem do kit (`core/` do pacote), instalado uma vez por máquina:

- **agentes**: `tech-lead`, `backend`, `frontend`, `dba`, `qa`, `reviewer`,
  `security`, `devops`, `docs`
- **skills**: `/sdlc-brainstorm`, `/sdlc-discovery`, `/bootstrap`,
  `/sdlc-skeleton`, `/sdlc-fatia`, `/sdlc-hardening`, `/pr`, `/seguranca`,
  `/adr`, `tdd-ciclo`
- **rules**: `comentarios.md`, `seguranca.md` — carregam em toda sessão

## O que entra no contexto, e quando

| Sempre, toda requisição | Sob demanda |
|---|---|
| `CLAUDE.md` do projeto | rule da linguagem, ao abrir um arquivo dela |
| `~/.claude/rules/comentarios.md` | corpo da skill, ao ser invocada |
| `~/.claude/rules/seguranca.md` | `docs/*`, quando o Claude lê |
| descrição (não o corpo) de cada skill | contexto do subagente, isolado |

Por isso o `CLAUDE.md` fica abaixo de 200 linhas e o detalhe de linguagem mora
em rule com `paths`: mexer numa migration não paga o custo das convenções de
React.

## Fluxo de trabalho

```bash
claude --agent tech-lead
```

A sessão assume o papel de tech-lead, que delega aos especialistas. Fases:

```
/bootstrap        cria os scaffolds de api/ e web/, scripts, lint, CI
/sdlc-skeleton    F0: fatia vertical mínima, CI verde
/sdlc-fatia       uma fatia do backlog por vez
/pr               branch, commits, pipeline, review, merge
/seguranca        auditoria + docs/seguranca.md
/sdlc-hardening   paga dívida no que já funciona
/adr              registra decisão de arquitetura
```

`/clear` entre fatias. O estado está em `docs/`, não no histórico da conversa.

## Garantia versus pedido

Vale registrar, porque muda o que se pode confiar:

- **Garantia**: o hook (`exit 2` impede a escrita), o CI (build falha) e o
  branch protection no GitHub. Valem sem ninguém olhando.
- **Pedido**: `CLAUDE.md`, rules e skills. São contexto — orientam, não
  impedem.

Regra prática: se algo **precisa** valer sempre, vai para hook ou CI. Se é
orientação de como escrever, vai para rule.

## Job `e2e`

O job `e2e` do `ci.yml` roda o mesmo `scripts/e2e.sh` do `make e2e` (ADR-033):
Postgres, API e web descartáveis, fluxo no Chromium com o Playwright. As
capturas e o relatório sobem como artifact por 7 dias.

Ele **não está no ruleset**. É um job novo, com navegador, Docker e três
processos, e ainda não se sabe se roda estável no runner. Entra nos checks
obrigatórios depois de algumas execuções verdes seguidas, pelo nome do job
(`e2e`), seguindo a ordem da seção abaixo.

## Ruleset da `main`

O ruleset "main protegida" exige PR, proíbe force push e deleção, e pede sete
checks pelo **nome do job**: `api`, `web`, `dependências (api)`,
`dependências (web)`, `varredura de segredo`, `análise estática (api)` e
`análise estática (javascript-typescript)`.

Renomear um job sem mexer no ruleset tranca todo merge: o check obrigatório
nunca mais roda. A ordem é tirar o nome antigo do ruleset, renomear e empurrar,
e incluir o nome novo depois do primeiro run (ADR-016). Por isso o nome de job
descreve o módulo, não a linguagem.
