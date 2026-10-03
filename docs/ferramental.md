# Ferramental — como o Claude Code está configurado neste projeto

Documenta por que a pasta `.claude/` existe e o que vem de onde. Se outra
pessoa clonar o repositório, é aqui que ela entende o setup.

## Duas metades

A configuração vive em dois lugares, e saber qual é qual evita duplicata:

| Metade | Onde | No Git? | O que tem |
|---|---|---|---|
| **Pessoal** | `~/.claude/` | não | agentes, skills, regras de estilo e segurança |
| **Do projeto** | `.claude/` + `CLAUDE.md` | **sim** | o que é específico do Ballast |

A metade pessoal vale em todos os projetos da máquina e é instalada uma vez.
A metade do projeto está versionada e vem com o repositório.

## O que está no repositório

```
CLAUDE.md                         # lei do projeto, toda sessão
.claude/
├── rules/
│   ├── go.md                     # carrega ao ler api/**/*.go
│   ├── sql.md                    # carrega ao ler api/migrations/**
│   ├── typescript.md             # carrega ao ler web/**/*.ts(x)
│   └── react.md                  # carrega ao ler web/**/*.tsx
├── hooks/bloqueia-segredo.sh     # bloqueia escrita em .env, *.key, *.pem
└── settings.json                 # registra o hook
.github/workflows/seguranca.yml   # gitleaks, govulncheck, pnpm audit, CodeQL
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

## Pendente de configurar no GitHub

Nada no repositório substitui isto, e sem ele os gates do CI são contornáveis:

**Settings → Branches → Add rule** na `main`:

- Require a pull request before merging → 1 approval
- Require status checks to pass → marque os jobs de `seguranca.yml`
- Do not allow bypassing the above settings
