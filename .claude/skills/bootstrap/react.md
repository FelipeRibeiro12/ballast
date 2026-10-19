# Bootstrap — React + Vite (TypeScript)

Confira `node --version` e `pnpm --version` antes.

## Scaffold

```bash
pnpm create vite@latest web --template react-ts
cd web
pnpm install
```

Sem `--` antes de `--template`: isso é exigência do `npm create`, não do `pnpm`.

Fixe o gerenciador no `package.json` para o CI não divergir da sua máquina:

```bash
pnpm pkg set packageManager="pnpm@$(pnpm --version)"
```

Scripts que vêm prontos:

```bash
pnpm dev
pnpm build
pnpm preview
```

## Teste

O Vite não instala runner de teste. Vitest é o que se integra sem configuração
paralela — ele reaproveita o `vite.config.ts`:

```bash
pnpm add -D vitest @testing-library/react @testing-library/jest-dom jsdom
```

No `vite.config.ts`, dentro do `defineConfig`:

```ts
test: {
  environment: 'jsdom',
  globals: true,
  setupFiles: './src/setupTests.ts',
}
```

E `src/setupTests.ts`:

```ts
import '@testing-library/jest-dom/vitest'
```

O sufixo `/vitest` importa: o import sem ele registra os matchers no `expect`
do Jest, e o seu `expect` é o do Vitest. Sem o sufixo, `toBeVisible` e os
outros matchers não existem em tempo de execução.

Adicione ao `package.json`: `"test": "vitest run"` e `"test:watch": "vitest"`.
O CI usa `vitest run` — sem `run`, o Vitest fica em modo watch e o pipeline
trava até o timeout.

## O que testar

Teste o que o usuário vê e faz, não o estado interno do componente. Busque por
papel e texto acessível, não por classe CSS:

```tsx
render(<Remessas />)
expect(await screen.findByRole('heading', { name: /remessas/i })).toBeVisible()
await userEvent.click(screen.getByRole('button', { name: /nova/i }))
```

Teste amarrado a classe ou a estrutura de DOM quebra em toda refatoração de
estilo, sem que nada tenha parado de funcionar.

## Lint

**Use o que o template trouxer** — ele pode vir com oxlint ou com eslint, e
trocar por preferência é trabalho sem retorno no primeiro dia.

O que importa, qualquer que seja o linter: **a regra de dependências de hook
tem que estar ligada como erro.** Efeito com dependência faltando é a origem da
maioria dos "atualiza sozinho" e "não atualiza nunca", e é o único problema de
React que o linter pega melhor que uma pessoa.

Se o template trouxe eslint sem ela:

```bash
pnpm add -D eslint-plugin-react-hooks
```

No oxlint, a regra correspondente já existe — confirme que está em `error`, não
em `warn`. Aviso que não quebra o build é aviso que o time aprende a ignorar.

## Chamada de API

- A URL base vem de variável de ambiente, nunca fixa no código. No Vite, só
  `VITE_*` chega ao cliente: `import.meta.env.VITE_API_URL`.
- **Tudo que vai para `VITE_*` é público.** Está no bundle, qualquer um lê.
  Chave de API de verdade nunca passa por aqui.
- Centralize o `fetch` em um módulo. Espalhar `fetch` pelos componentes torna
  impossível mudar cabeçalho, erro ou base depois.
- Trate os três estados em toda tela que busca dado: carregando, erro, vazio.
  Esse é o item que mais falta em código de primeira versão.

## Estrutura

Comece simples:

```
src/
├── main.tsx
├── App.tsx
├── api/          # cliente HTTP e tipos da API
├── pages/
└── components/
```

Não crie design system, pasta de átomos ou biblioteca de componentes na
primeira tela. Componentize quando o mesmo pedaço aparecer três vezes.

## CI

```yaml
      - uses: pnpm/action-setup@v4
      - uses: actions/setup-node@v4
        with:
          node-version: 22
          cache: pnpm
      - run: pnpm install --frozen-lockfile
      - run: pnpm lint
      - run: pnpm test
      - run: pnpm build
```

`build` no CI pega erro de tipo que o `dev` não mostra.

## .gitignore

O template cobre. Confira: `node_modules`, `dist`, `.env`, `coverage`.
