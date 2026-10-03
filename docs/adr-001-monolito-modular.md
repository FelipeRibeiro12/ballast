# ADR-001: Monolito modular em vez de microsserviços

**Status:** aceito
**Data:** 2026-10-03

## Contexto

O projeto é desenvolvido por uma pessoa. Foi considerado dividir o backend em
microsserviços, cada um numa linguagem diferente (Go, TypeScript/Nest, Java/
Spring, Python), como forma de aprender as três stacks e enriquecer o
portfólio.

## Decisão

Monolito modular em Go, com pacotes por domínio: `contas`, `lancamentos`,
`recorrencia`, `cartao`, `metas`, `orcamento`, `cotacoes`.

## Justificativa

O domínio é transacional. Lançar uma compra parcelada no cartão debita a
fatura, cria N parcelas futuras, atualiza o saldo projetado e pode abater uma
meta — tudo em uma operação que precisa ser atômica. Num monolito é uma
transação de banco; distribuído, é uma saga com compensação, idempotência e
reconciliação. Inconsistência num app de dinheiro não é aceitável, e o custo de
garantir consistência distribuída é desproporcional ao tamanho do problema.

Em polyglot, o tipo `Dinheiro` seria implementado quatro vezes, com precisão
decimal diferente em cada linguagem. Erro de arredondamento na fronteira entre
serviços é difícil de achar e corrói a confiança no app inteiro.

Para um desenvolvedor, quatro toolchains, quatro pipelines e quatro deploys
multiplicam a manutenção sem nenhum ganho: não existe time concorrendo pelo
mesmo deploy, nem parte com perfil de carga distinto.

Um monolito modular bem separado por domínio pode ser dividido depois, se
algum dia houver motivo. O caminho inverso quase nunca acontece.

## Alternativas descartadas

- **Microsserviços poliglotas (Go + Nest + Spring)** — descartado pelos motivos
  acima. Para aprender as outras stacks, projetos separados e menores ensinam
  mais rápido e não arrastam este.
- **Microsserviços em Go só** — reduz o custo de linguagem, mantém o custo de
  consistência distribuída. Não há problema que justifique.
- **Serverless por função** — transação de longa duração e conexão com banco
  são atrito conhecido; não compensa aqui.

## Consequência

Fronteira entre domínios é disciplina de código, não de rede. Pacote não
importa o interno de outro: a comunicação é por interface definida no pacote
que consome. Se essa disciplina cair, a separação futura fica caro.

## Em aberto

O serviço de previsão em Python (ADR-007) é a única separação prevista, e só na
fatia 27.
