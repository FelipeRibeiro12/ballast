---
name: piloto
description: "Executa fatias do backlog do começo ao fim sem pedir input: branch, delegação, commits, PR, pipeline, revisão e merge. Use quando você vai sair da frente do computador."
disable-model-invocation: true
---

# Piloto

Roda fatia do backlog de ponta a ponta, sozinho. Você digita uma vez e sai.

```
/piloto              uma fatia, a próxima do backlog
/piloto F5           uma fatia específica
/piloto até F8       repete até fechar a F8
/piloto 3 fatias     repete três vezes
```

Você é o tech-lead aqui. Vale tudo que está no seu arquivo: decida sozinho,
registre, siga. Esta skill só descreve o laço.

## Antes de começar

Confira e resolva sozinho, sem perguntar:

- Árvore limpa. Se tem coisa não commitada, commite com mensagem honesta ou
  guarde no stash e anote no diário.
- Está na `main` e atualizada. Se não, troque e puxe.
- A fatia alvo existe no `docs/backlog.md` e as anteriores estão fechadas. Se
  a ordem não bate, resolva pela dependência real e registre no diário.

## O laço, por fatia

**1. Escopo.** Leia a fatia no backlog e os contratos. Escreva o escopo em uma
frase. Se faltar decisão, tome e abra ADR. Não pergunte.

**2. Branch.** `feat/f<n>-<slug>` a partir da `main`.

**3. Delegue, na ordem.** Pule o que a fatia não precisa:
`dba` → `backend` → `frontend` → `qa` → `docs`

Cada um volta com resumo. Leia, decida o que entra agora e o que vira dívida.
Dívida vira linha no backlog com dono e com a fatia que vai pagar.

**4. Commits.** Um por passo lógico, conventional, mensagem em português no
imperativo. Não commite com teste quebrado.

**5. PR.** Push e abra. No corpo: o que muda, por quê, como testar, o que
ficou de fora, qual o risco.

**6. Pipeline.** Acompanhe. Vermelho, você conserta:

- Leia o log do passo que falhou. Não chute.
- Conserte a causa, commite, empurre.
- **Duas tentativas por problema distinto.** Na terceira, pare e escreva no
  diário o que tentou e o que observou.
- Nunca contorne removendo teste, marcando skip ou desligando gate. Se a
  saída passa por aí, a saída está errada.

**7. Revisão.** Delegue ao `reviewer`. Se o diff toca autenticação,
autorização, sessão, criptografia, upload, query crua ou dependência nova,
delegue também ao `security`.

Só **Bloqueia** segura o merge. Dívida e Sugestão viram linha no backlog.
Código feio que funciona e está testado não segura entrega.

**8. Merge.** Verde e sem bloqueante: `--merge`, apaga a branch, volta para a
`main` e puxa.

**9. Feche.** Atualize o backlog, escreva a entrada no `docs/diario.md` e
atualize sua memória de projeto.

**10. Próxima.** Se ainda falta fatia no alvo, volte ao passo 1. Senão, pare.

## Quando parar antes do fim

A lista fechada do seu arquivo de agente vale aqui inteira. Repetindo as que
mais aparecem neste laço:

- Terceira tentativa no mesmo problema de pipeline
- A fatia cresceu muito além do escrito
- Precisaria contrariar um ADR aceito
- Achou segredo no histórico
- A única saída passa por desligar uma grade
- Qualquer coisa irreversível ou que gere cobrança

Ao parar, escreva no diário onde parou, o que tentou e qual decisão falta.
Deixe a árvore limpa e a branch empurrada, para quem voltar não achar trabalho
pela metade sem rastro.

## O resumo final

Quando terminar ou parar, uma mensagem curta:

- Fatias fechadas, com número e título
- ADRs abertos
- Dívida nova, em uma linha cada
- O que precisa dele, se precisa de algo

O detalhe fica no `docs/diario.md`. A mensagem é só para ele saber se precisa
olhar.

## O que torna isso seguro

Não é confiança, é grade. Você pode decidir sozinho porque o pipeline barra
teste quebrado, o branch protection barra push direto, a varredura barra
segredo e o ADR registra toda decisão.

Se você se pegar querendo desligar uma dessas para avançar, pare. É o sinal
mais claro de que o caminho está errado.
