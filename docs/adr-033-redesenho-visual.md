# ADR-033: Redesenho visual do web e verificação em navegador

**Status:** aceito
**Data:** 2026-10-20

## Contexto

O web cresceu uma seção por fatia e hoje é uma página só, com contas, saldo
projetado, lançamentos, recorrências e cartão empilhados. O CSS é o mínimo de
cada fatia, sem paleta, sem modo escuro e sem navegação. O dono pediu o app
"bem feito, bonito e moderno".

Até aqui nenhuma tela foi aberta num navegador de verdade: toda a suíte do web
simula `fetch` em jsdom, e foi assim que o proxy do Vite ficou quebrado da F2
à F8 sem ninguém ver (diário, PR #14).

## Decisão

### Verificação em navegador

- **Playwright** (`@playwright/test` 1.63.0, Apache-2.0, sem custo, versão
  fixa, só em `devDependencies`) com Chromium.
- `make e2e` sobe um Postgres descartável, a API e o web em portas altas,
  roda um fluxo de ponta a ponta (cadastro, conta, cartão, lançamento,
  recorrência, transferência, saldo projetado, faturas, sair) e tira capturas
  de cada tela em 360×780 e 1280×800, no claro e no escuro. Derruba tudo no fim.
- As capturas vão para `web/e2e/capturas/`, fora do git. Elas servem para quem
  revisa olhar a tela; não são teste de regressão de pixel.
- No CI entra como job próprio, `e2e`, fora dos checks obrigatórios do
  ruleset até rodar estável algumas vezes.

### Navegação

- Quatro seções: **Início** (saldo das contas, saldo projetado, vencidas e as
  próximas previstas), **Lançamentos**, **Contas** (contas, cartões e faturas)
  e **Recorrências**.
- Rota por hash (`#/inicio`, `#/lancamentos`, `#/contas`, `#/recorrencias`),
  sem biblioteca: o botão voltar funciona e o service worker não precisa
  conhecer rotas, porque o hash não chega ao servidor.
- No celular, barra inferior fixa com ícone e rótulo; a partir de 768px,
  navegação no topo. Cabeçalho com o nome do app e "Sair".

### Sistema visual

- Tokens em CSS custom properties: cores (neutros, primária, receita, despesa,
  atenção, transferência), espaçamento, raio, sombra e tipografia.
- **Modo escuro** por `prefers-color-scheme`, com um seletor no cabeçalho
  (claro, escuro, do sistema) guardado em `localStorage`, com `try/catch`: se o
  armazenamento falhar, vale o do sistema.
- Fonte do sistema (`system-ui`), sem fonte baixada: não depende de rede e não
  pesa no shell do PWA. Números de dinheiro com `font-variant-numeric:
  tabular-nums`.
- Componentes em CSS puro: cartão, botões (primário, secundário, discreto,
  perigo), campo, selo de situação (Previsto, Vencida, Paga, Cancelado), estado
  vazio e esqueleto de carregamento.
- Ícones em SVG inline num módulo próprio, feitos à mão e poucos. Sem
  biblioteca de ícones nem de componentes.
- Formulários de criar abrem num painel a partir de um botão primário; no
  celular, um botão flutuante "+" na seção de lançamentos.

### Acessibilidade

- Contraste AA nos dois modos, foco visível, alvo de toque de pelo menos 44px,
  `prefers-reduced-motion` respeitado, e sinal de dinheiro sempre em texto além
  da cor.

### O que não muda

- Contrato da API, regras de dinheiro e datas, e o que cada tela faz. Os nomes
  acessíveis dos controles mudam o mínimo possível, para os testes existentes
  continuarem provando o mesmo comportamento.

## Justificativa

Uma tela por seção acaba com a rolagem infinita da página única e dá um lugar
para cada fatia futura. Rota por hash entrega voltar e link direto sem trazer
roteador.

CSS próprio com tokens é suficiente para um app deste tamanho e mantém o
bundle pequeno. Biblioteca de componentes traria uma dependência grande para
botão, campo e cartão.

O Playwright paga a dívida mais antiga do projeto: ver a tela de verdade.

## Alternativas descartadas

- **Tailwind ou biblioteca de componentes**: dependência e passo de build a
  mais para o que tokens e algumas classes resolvem.
- **React Router**: rota por hash com quatro seções cabe em um hook.
- **Regressão visual por pixel**: frágil entre máquinas e fontes; as capturas
  são para olhar, não para comparar.
- **Fonte baixada (Inter e semelhantes)**: peso e requisição externa para
  ganho pequeno sobre a fonte do sistema.

## Consequência

`make e2e` e o job `e2e` passam a existir. Cada fatia de interface daqui para
frente roda o e2e e olha as capturas antes do PR.
