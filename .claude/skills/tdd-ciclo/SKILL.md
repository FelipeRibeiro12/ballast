---
name: tdd-ciclo
description: "Como implementar: teste que falha, versão mais simples que passa, verde, entrega. Melhoria vem depois que o código foi aceito. Use ao implementar qualquer funcionalidade nova ou corrigir bug."
---

# Ciclo de implementação

**Fazer funcionar primeiro. Melhorar depois que foi aceito.** Nessa ordem,
sempre.

## O ciclo

1. **Teste que falha.** O menor teste que descreve o comportamento pedido.
   Rode e confirme que ele falha, e que falha pelo motivo certo. Teste que
   falha por erro de compilação não provou nada.
2. **A versão mais burra que passa.** Pode ser feia. Pode ser hardcoded. Sem
   camada de abstração antecipada, sem interface para uma implementação só,
   sem generalizar para caso que ainda não existe.
3. **Verde.** Rode a suíte inteira, não só o teste novo. Commit.
4. **Entregue.** A fatia vai para revisão com o código funcionando.

Melhoria estrutural não entra aqui. Ela acontece depois, e a seção abaixo
explica onde.

## O que impede a entrega de subir

Não é a sua opinião sobre a qualidade do código. É o pipeline:

- Teste vermelho barra o merge
- Lint vermelho barra o merge
- Varredura de segredo barra o merge

Se passou nos três e o revisor não achou nada que bloqueia, o código vai. Essa
é a régua, e ela é a mesma todo dia.

## O que o revisor decide

Três categorias, e só uma impede o merge:

| Achado | O que acontece |
|---|---|
| **Bloqueia** | quebra, furo de segurança ou perde dado. Corrige agora, no mesmo PR |
| **Dívida** | defeito real sem risco imediato, ou melhoria estrutural. Vai para o backlog com dono |
| **Sugestão** | preferência. Decide e segue |

Código feio que funciona e está testado **não bloqueia**. Ele vira dívida.

## Onde a melhoria acontece

Depois do merge, na fatia de endurecimento. E o combinado é este:

**Toda melhoria adiada vira linha no backlog, com a fatia que vai pagar.**

"Melhoro depois" sem endereço nunca acontece, porque a próxima fatia é sempre
mais interessante que arrumar a anterior. A linha no backlog é o endereço.

Quando a melhoria chega:

- **Regra das três.** Duplicação vira abstração na terceira aparição. Na
  primeira é coincidência, na segunda é suspeita.
- Pattern que você não explica em uma frase não entra.
- Extrair interface só quando existem duas implementações de verdade, ou
  quando o teste precisa de um dublê.
- SOLID onde dói. Princípio aplicado onde não há dor deixa o código pior.

## O que testar, no mínimo

- Caminho feliz
- Cada erro esperado, com o erro específico e não só "falhou"
- Controle de acesso: quem não pode, não consegue
- Borda: zero, vazio, limite, duplicado

## Nunca

- Commitar com teste quebrado
- Apagar ou afrouxar teste para a suíte passar. Se o teste está errado, diga
  que está errado e por quê, e espere resposta
- Adiantar funcionalidade que ninguém pediu
- Segurar uma entrega pronta por causa de melhoria que não bloqueia
