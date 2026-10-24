# ADR-052: Cabeçalhos de segurança do front e 429 nas leituras

**Status:** aceito
**Data:** 2026-10-24

## Contexto

O front de produção sai do Vercel (ADR-044) sem nenhum cabeçalho de segurança.
Um script injetado leria a tela inteira, e o app mostra saldo e patrimônio.
Separado disso, o limite por IP do ADR-049 responde 429 também nas leituras, e
a tela dizia só "Não foi possível carregar".

## Decisão

### Cabeçalhos

O `web/vercel.json` ganha uma rota no começo da lista, com `continue: true`,
que põe os cabeçalhos em toda resposta, inclusive nas de `/api`. Ela vem antes
da rota de `/api` e do `filesystem` para não ser pulada. O `vercel.json` usa
`routes` (ADR-044), então não existe a chave `headers`.

- **CSP:** `default-src 'self'; script-src 'self'; style-src 'self'; img-src
  'self' data:; connect-src 'self'; object-src 'none'; base-uri 'self';
  form-action 'self'; frame-ancestors 'none'`.
- **HSTS:** `max-age=63072000; includeSubDomains`, sem `preload`, que é difícil
  de desfazer.
- **X-Content-Type-Options:** `nosniff`.
- **Referrer-Policy:** `strict-origin-when-cross-origin`.
- **Permissions-Policy:** câmera, microfone, geolocalização, pagamento e USB
  desligados. O app não usa nenhum.
- **`/sw.js`:** `Cache-Control: no-cache`, para o navegador revalidar o service
  worker e a atualização do PWA chegar.

### Sem `unsafe-inline`

O `pnpm build` gera um `index.html` com um `<script type="module">` externo,
CSS em arquivo e nenhum `<style>` ou `style=` no HTML. As larguras de barra do
app usam `style={{}}` do React, que passa pelo CSSOM e a CSP não bloqueia. Por
isso `style-src` fica só em `'self'`. Se uma biblioteca futura injetar `<style>`,
a tela quebra no navegador e o conserto é nonce ou hash, não `unsafe-inline`.

`connect-src 'self'` basta porque a API é mesma origem (ADR-044). O WebSocket de
cripto não existe: a leitura é pela API.

### 429 nas leituras

Os leitores de dados em `api.ts` lançam `LimiteTentativasError` no 429, como o
login já fazia. O `mensagemDeLeitura` mostra "Muitas requisições em pouco
tempo. Espere um instante e tente de novo." A mensagem não promete um prazo
porque o `Retry-After` de leitura não é contrato.

## Testes

O `web/src/producao.test.ts` confere cada cabeçalho, que a rota vem antes da
de `/api`, que a CSP não tem `'unsafe-eval'`, curinga nem `unsafe-inline` em
script, e a regra do `/sw.js`. O `leitura.test.ts` e o `api.test.ts` cobrem o 429.

## Consequências

- A CSP só vale no deploy do Vercel. O `vite dev` não a aplica, então uma
  violação aparece primeiro no preview de produção.
- Sem relatório de violação (`report-uri`). Entra se a CSP começar a quebrar
  algo em produção.
