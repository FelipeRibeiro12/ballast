# ADR-030: PWA instalável com vite-plugin-pwa

**Status:** aceito
**Data:** 2026-10-20

## Contexto

O ADR-004 escolheu o PWA como cliente único. A F9 torna o web instalável no
Android, no iOS e no desktop: manifest, ícones e service worker. O backlog já
pedia `vite-plugin-pwa` com `devOptions.enabled: false`, porque service worker
no desenvolvimento cacheia asset e atrapalha.

O app mostra dado financeiro de uma pessoa. Service worker com cache de
resposta da API guardaria saldo e lançamento no aparelho e poderia mostrar
número velho como se fosse atual.

## Decisão

- **Dependência.** `vite-plugin-pwa` 1.3.0, em `devDependencies` e com versão
  fixa. Licença MIT, mantido pelo projeto vite-pwa, sem custo. Ele traz
  `workbox-build` e `workbox-window` 7.4.1, também MIT. A 2.0.0 saiu em
  2026-10-03 com mudança de versão maior; fica para quando tiver mais
  estrada, e a troca é só de versão.
- **Ícones.** Gerados uma vez a partir do SVG do app com
  `@vite-pwa/assets-generator` via `pnpm dlx`, sem entrar no `package.json`.
  Os PNG ficam commitados em `web/public`.
- **Service worker.** Modo `generateSW`. Faz precache só do shell do app
  (HTML, JS, CSS, ícones). **Nenhuma rota da API é cacheada**: sem
  `runtimeCaching`, e o `navigateFallback` tem como denylist os mesmos
  prefixos de `web/src/rotasDaApi.ts`. Sem rede, a tela abre e mostra o erro de
  conexão que já existe, nunca dado guardado.
- **Atualização.** `registerType: 'prompt'`: quando sai versão nova, a tela
  mostra "Nova versão disponível" com o botão "Atualizar". Recarregar sozinho
  no meio de um formulário perderia o que o usuário digitou.
- **Desenvolvimento.** `devOptions.enabled: false`. `make dev` continua sem
  service worker.
- **Manifest.** `name` Ballast, `lang` pt-BR, `start_url` e `scope` `/`,
  `display` standalone, ícones 192, 512 e 512 maskable, `theme_color` e
  `background_color` do CSS. No iOS, `apple-touch-icon` de 180 e as metas de
  app em tela cheia no `index.html`.

## Justificativa

`vite-plugin-pwa` gera o service worker a partir do build, com hash de cada
asset; escrever à mão exigiria manter essa lista sincronizada.

Não cachear a API é a escolha que mantém a regra do projeto: o número que a tela
mostra é o que o servidor calculou agora. Offline de verdade, com fila de
escrita, exige idempotência na escrita de dinheiro, que é dívida aberta.

## Alternativas descartadas

- **Service worker escrito à mão**: mesma função, com a lista de assets para
  manter.
- **`registerType: 'autoUpdate'`**: troca a versão sem avisar e pode perder um
  formulário pela metade.
- **Cache da API com `NetworkFirst`**: guardaria dado financeiro no aparelho e
  mostraria saldo velho sem rede.
- **`@vite-pwa/assets-generator` como dependência**: só roda uma vez; não paga
  ficar no `package.json`.

## Consequência

Rota nova da API entra em `rotasDaApi.ts`, e o teste da correção do proxy já
obriga isso; a denylist do service worker usa a mesma lista. Modo offline com
dado guardado fica para quando houver idempotência e criptografia local, com
ADR próprio.
