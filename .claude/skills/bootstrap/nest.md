# Bootstrap — NestJS (TypeScript)

Confira `node --version` e `pnpm --version` antes. Se o CLI do Nest gerar algo
diferente do descrito aqui, siga o que ele gerou e avise.

## Scaffold

Sem instalar o CLI global:

```bash
pnpm dlx @nestjs/cli new api --package-manager pnpm --strict
cd api
```

`--strict` liga o modo estrito do TypeScript desde o começo. Ligar depois, com
o projeto cheio, é muito mais caro.

O scaffold já vem com Jest configurado, ESLint, Prettier e esta estrutura:

```
src/
├── main.ts              # bootstrap da aplicação
├── app.module.ts        # módulo raiz
└── app.controller.ts
test/                    # testes e2e
```

Scripts que o Nest cria — use estes, não invente outros:

```bash
pnpm start:dev    # dev com reload
pnpm build
pnpm test         # unitário
pnpm test:e2e
pnpm test:cov
pnpm lint
```

## Um recurso novo

```bash
pnpm dlx @nestjs/cli generate resource shipments
```

Ele pergunta o tipo de transporte (REST) e se quer os endpoints CRUD. Gera
módulo, controller, service, DTOs e os arquivos de teste. Use sempre o gerador:
ele mantém o padrão e registra o módulo para você.

Para peças isoladas: `generate module|controller|service|guard|pipe <nome>`.

## Configuração e validação

Duas coisas que todo projeto Nest precisa e o scaffold não liga sozinho.

**Variáveis de ambiente** — nunca `process.env` espalhado pelo código:

```bash
pnpm add @nestjs/config
```

Registre o `ConfigModule` como global no `app.module.ts` e leia tudo por ele.
Valide as variáveis na subida: aplicação que sobe sem uma variável obrigatória
e quebra na primeira requisição é pior que aplicação que não sobe.

**Validação de entrada** — o DTO não valida nada sozinho:

```bash
pnpm add class-validator class-transformer
```

No `main.ts`, um `ValidationPipe` global com `whitelist: true` (descarta campo
não declarado) e `transform: true`. Sem isso, o DTO é só documentação.

## Banco

Prisma é o caminho mais direto:

```bash
pnpm add -D prisma
pnpm add @prisma/client
pnpm dlx prisma init
```

O schema vai em `prisma/schema.prisma`, e migrations em `prisma/migrations/` —
faixa do agente `dba`, não do backend.

```bash
pnpm dlx prisma migrate dev --name cria_remessas
pnpm dlx prisma generate
```

TypeORM é a alternativa quando o projeto precisa de controle fino de SQL ou já
usa. Escolha uma e registre no ADR.

## Camadas

O Nest empurra para controller → service → repositório. Respeite isso:

- **Controller** — HTTP: recebe, valida via DTO, devolve. Sem regra de negócio.
- **Service** — a regra. É o que os testes unitários cobrem.
- **Repositório / Prisma** — acesso a dado. Só aqui.

Injeção de dependência é o que torna o service testável: o teste passa um
dublê no lugar do repositório. Não instancie dependência com `new` dentro do
service.

## Teste

Unitário fica ao lado do arquivo (`*.spec.ts`), e2e em `test/` (`*.e2e-spec.ts`).

O `Test.createTestingModule` monta um módulo só com o que o teste precisa. Para
o e2e, `supertest` já vem instalado e bate no app de verdade.

Não mocke o que você está testando. Se o teste do service mocka o service,
ele não testa nada.

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
```

`--frozen-lockfile` faz o CI falhar quando o lockfile está desatualizado, em
vez de instalar versão diferente da sua e mascarar o problema.

## .gitignore

O scaffold cobre o essencial. Confira que tem: `node_modules`, `dist`,
`coverage`, `.env`.
