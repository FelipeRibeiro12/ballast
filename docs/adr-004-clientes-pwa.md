# ADR-004: PWA em React como cliente único

**Status:** aceito
**Data:** 2026-10-03

## Contexto

A intenção é publicar o app e atender celular, desktop e web. Foi considerado
construir três clientes: app mobile nativo, app desktop e site responsivo.

O momento de uso dominante é o celular: registrar um gasto no restaurante,
conferir saldo na fila do mercado.

## Decisão

Um cliente: React com Vite, mobile-first, configurado como PWA (manifest e
service worker).

## Justificativa

PWA cobre os três alvos com um código, um deploy e uma superfície de bug. No
Android instala na tela inicial e se comporta como app; no desktop, Chrome e
Edge instalam em janela própria; na web, é o site.

Três clientes separados, para um desenvolvedor, significam triplicar o trabalho
de cada tela antes de o produto ter provado que vale.

## Alternativas descartadas

- **React Native / Expo** — app mais nativo, mas a interface é reescrita: o
  dobro do trabalho para um produto ainda não validado.
- **Tauri ou Electron no desktop** — PWA instalado entrega o mesmo para este
  caso de uso. Não há recurso de sistema operacional necessário.
- **Next.js** — SSR não se justifica em app privado atrás de login.

## Limitação reconhecida

No iOS, PWA tem restrições: notificação push só funciona se o usuário
adicionar à tela inicial, e não há presença na App Store. Se a loja virar
necessidade, o caminho é **Capacitor**, que embala o mesmo React em app
publicável reaproveitando a interface inteira.

## Consequência

Toda tela é desenhada primeiro em largura de celular. Gráfico precisa ser
legível em 360px — o que limita as opções de visualização e é melhor descobrir
agora que depois.
