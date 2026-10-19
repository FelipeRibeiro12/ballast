---
paths:
  - "**/*.ts"
  - "**/*.tsx"
---

# TypeScript

## Tipos

- `strict` ligado. Se o projeto não está em strict, isso é dívida registrada,
  não uma escolha.
- **`any` não entra.** Quando o tipo é desconhecido de verdade, `unknown` e
  estreite com verificação. `any` desliga o compilador justamente onde ele
  seria útil.
- Não use `as` para calar o compilador. Se precisou, ou o tipo está errado ou
  falta uma verificação — resolva a causa.
- `?.` e `??` em vez de encadear `&&`.
- Tipo de retorno explícito em função exportada. Inferência é ótima dentro do
  arquivo e péssima como contrato público.
- Prefira `type` para união e forma de dado; `interface` quando algo vai ser
  implementado ou estendido.
- União de literais em vez de `enum`: `type Estado = 'criado' | 'em_transito'`.

## Erros

- Nunca `catch` vazio. Nunca `catch` que só loga e segue como se nada tivesse
  acontecido.
- Erro lançado é classe de erro, não string.
- `async` sem `await` em promessa é bug silencioso: toda promessa é aguardada
  ou explicitamente tratada.
- Validação de entrada na borda (DTO, schema). Depois da borda, o tipo vale.

## Nest, quando for o caso

- **Controller** — HTTP: recebe, valida via DTO, devolve. Sem regra de negócio.
- **Service** — a regra. É o que o teste unitário cobre.
- **Repositório** — acesso a dado. Só aqui.
- Dependência entra por injeção no construtor, nunca `new` dentro do service —
  é o que permite o dublê no teste.
- DTO com `class-validator` e `ValidationPipe` global com `whitelist: true`.
  Sem o pipe, o DTO é só documentação.
- Variável de ambiente só pelo `ConfigModule`, nunca `process.env` espalhado.
- Módulo por domínio, não por camada técnica.

## Comandos

```bash
pnpm lint
pnpm test
pnpm test:e2e
pnpm build      # roda antes do typecheck em projeto com tipo gerado
```
