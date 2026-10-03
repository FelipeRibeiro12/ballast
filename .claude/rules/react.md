---
paths:
  - "web/**/*.tsx"
  - "web/src/components/**"
  - "web/src/pages/**"
---

# React

## Componente

- Função, não classe.
- Um componente faz uma coisa. Se o nome precisa de "e", são dois.
- Props tipadas explicitamente. Sem `any`, sem `object`.
- Não componentize na primeira tela. Extraia quando o mesmo pedaço aparecer
  três vezes.

## Estado

- Estado no nível mais baixo que funciona. Subir estado que só um componente
  usa causa re-render em toda a árvore.
- Estado derivado se calcula na renderização, não se guarda num `useState`
  sincronizado por efeito. Dois estados que precisam concordar já divergiram.
- `useEffect` é para sincronizar com algo de fora (rede, DOM, timer). Não é
  para reagir a mudança de prop.
- Array de dependência completo. A regra do lint sobre isso pega bug de
  verdade: dependência faltando é a origem da maioria dos "atualiza sozinho" e
  "não atualiza nunca".
- Todo efeito que assina algo precisa da função de limpeza.
- `key` de lista é identidade estável do item. Índice como `key` corrompe
  estado quando a lista reordena.

## Dado de fora

- **Os três estados, sempre**: carregando, erro, vazio. É o que mais falta em
  código de primeira versão.
- Erro de API nunca é engolido. Se falhou, o usuário sabe.
- `fetch` centralizado num módulo, não espalhado pelos componentes.
- URL base por variável de ambiente. No Vite, `import.meta.env.VITE_API_URL`.
- **Tudo com prefixo `VITE_` é público** — está no bundle. Chave de verdade
  nunca passa por aí.

## Acessibilidade

- Elemento interativo é `button` ou `a`, não `div` com `onClick`.
- Todo campo de formulário tem `label` associado.
- Imagem tem `alt`; decorativa tem `alt=""`.
- Foco visível: não remova o outline sem pôr outro no lugar.

## Teste

Busque por papel e texto acessível, nunca por classe CSS ou estrutura de DOM —
teste amarrado a estilo quebra em refatoração sem nada ter parado de funcionar.

```tsx
screen.getByRole('button', { name: /salvar/i })
```

Teste o que o usuário faz, não o estado interno do componente.
