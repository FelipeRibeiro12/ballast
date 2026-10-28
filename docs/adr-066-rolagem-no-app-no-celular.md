# ADR-066: Rolagem dentro do .app no celular

**Status:** aceito
**Data:** 2026-10-28

## Contexto

No Safari do iPhone a barra de navegação de baixo flutuava com conteúdo
aparecendo por baixo, e o toque caía no botão vizinho. No Mac funcionava. A
barra é `position: fixed`, e com a página rolando a barra do Safari encolhe e
cresce, o que move a viewport enquanto o toque acontece.

## Decisão

- Abaixo de 48rem, `html` e `body` não rolam e quem rola é o `.app`, com
  `height: 100dvh`. A barra do Safari fica parada, e a barra fixa fica no lugar.
- O painel aberto trava o `.app` no lugar do `body`.
- A troca de seção rola o `.app` para o topo, além da janela.
- `viewport-fit=cover` no `index.html`, para o `env(safe-area-inset-*)` valer na
  PWA instalada.
- Acima de 48rem a página rola como antes.

## Consequência

Não testei num iPhone: a correção segue a causa conhecida e fica por conta do
dono confirmar no aparelho. A barra do Safari não minimiza mais ao rolar.
Se o toque ainda errar, o próximo passo é tirar a barra de dentro do `header`
sticky.
