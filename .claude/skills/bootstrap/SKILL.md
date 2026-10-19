---
name: bootstrap
description: "Inicializa um projeto novo em uma stack: scaffold, scripts de build e teste, lint, primeiro teste e CI. Use depois que a stack foi decidida, para criar o repositório do zero."
disable-model-invocation: true
---

# Inicializar um projeto

Vale para qualquer stack. Os comandos específicos estão nos arquivos de
referência desta pasta — **leia só o da stack que você vai usar**:

- Nest (TypeScript, backend) → `nest.md`
- Go (backend) → `go.md`
- React + Vite (frontend) → `react.md`

## Regra antes de qualquer comando

**Confirme a versão instalada antes de gerar o scaffold.** Gerador de projeto
muda de uma versão para outra, e comando decorado de versão antiga cria um
projeto que não compila.

```bash
node --version && pnpm --version    # ou npm
go version
```

Se o gerador tem flag que você não tem certeza, rode o `--help` dele em vez de
adivinhar. Se o resultado divergir do que a referência descreve, **siga o que a
ferramenta fez** e me avise da diferença — não force o layout antigo.

Não instale dependência que não foi pedida. Não adicione biblioteca "que todo
projeto tem".

## Ordem, em qualquer stack

1. **Scaffold** com a ferramenta oficial da stack. Nunca escreva à mão o que o
   gerador cria.
2. **`.gitignore`** antes do primeiro commit. Build, dependência, `.env`,
   cobertura, arquivo de editor.
3. **`.env.example`** com as chaves e valores vazios. O `.env` real nunca entra.
4. **Scripts padronizados.** Todo projeto meu expõe os mesmos verbos, seja qual
   for a stack: `dev`, `build`, `test`, `lint`. Se a stack não usa npm scripts,
   um `Makefile` com os mesmos alvos resolve. Assim o CI e eu usamos o mesmo
   comando em qualquer repositório.
5. **Um teste que passa.** Trivial, só para provar que o runner funciona.
6. **Um teste que falha**, de propósito. Confirme que o comando fica vermelho.
   Desfaça. Runner que sempre passa é pior que não ter teste.
7. **Lint e formatador**, com a configuração padrão da stack. Não invente
   regra de estilo no primeiro dia.
8. **CI** rodando lint + teste. Quebre um teste, confirme que o pipeline fica
   vermelho, desfaça.
9. **Gate de segurança no CI.** Copie `ci-seguranca.yml` da skill `/seguranca`
   para `.github/workflows/`: varredura de segredo, auditoria de dependência e
   análise estática. É muito mais barato ligar agora que depois.
10. **README** com pré-requisitos, como rodar e como testar. Até 10 linhas de
    comando.
11. **Commit inicial**: `chore: inicializa projeto`.
12. **Branch protection** na `main`, no painel do repositório: pipeline verde
    obrigatório, uma aprovação, sem push direto. Sem isso, todo gate acima é
    contornável. Avise o usuário se você não tem acesso para configurar.

## CI mínimo

Serve para qualquer stack — troque os comandos:

```yaml
name: ci
on: [push, pull_request]
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      # setup da stack aqui
      - run: <instalar dependências>
      - run: <lint>
      - run: <test>
```

Comece com isso. Matriz de versões, cache elaborado e paralelismo entram quando
o tempo de execução doer de verdade — não antes.

## Critério de pronto

- [ ] `git clone` + um comando de setup + um comando de start funciona do zero
- [ ] `test` roda e passa
- [ ] `test` fica vermelho quando eu quebro um teste
- [ ] `lint` roda e passa
- [ ] CI roda no push e reflete os dois estados acima
- [ ] Nenhum segredo no repositório, `.env.example` no lugar
- [ ] README com o passo a passo verificado

## Depois

A próxima fase é `/sdlc-skeleton`: a fatia vertical que atravessa a stack
inteira. Este bootstrap entrega o repositório; o skeleton entrega o encanamento
ponta a ponta.
