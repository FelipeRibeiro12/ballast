---
name: seguranca
description: "Auditoria completa de segurança contra o checklist do projeto, e criação do docs/seguranca.md com dono e status por item. Use antes de expor algo na internet, antes de uma entrega, e na revisão periódica."
disable-model-invocation: true
---

# Auditoria de segurança

Os mínimos de segurança não são uma lista única: eles moram em três lugares
diferentes, com donos diferentes e formas diferentes de verificar.

| Categoria | Dono | Como se verifica | Referência |
|---|---|---|---|
| Código | `backend`, `frontend`, `dba` | lendo o repositório | `codigo.md` |
| Infra e deploy | `devops` | olhando o ambiente de verdade | `infra.md` |
| Processo | o time | pelo que acontece de forma recorrente | `processo.md` |

**Leia só a referência da categoria que você vai auditar.** Auditar as três de
uma vez produz relatório longo que ninguém age em cima.

## Por que isso não é um agente só

Metade dessa lista não é código. Dá para ler o repositório e afirmar que a senha
usa argon2; não dá para afirmar, lendo o repositório, que o backup roda, que o
alerta chega em alguém ou que o HTTPS está ativo. O agente `security` lê o que
está escrito; o resto alguém tem que confirmar no ambiente.

Por isso o artefato desta skill não é um parecer: é o `docs/seguranca.md`, com
**dono e status por item**. Em time de verdade é o checklist que sustenta a
segurança, não a memória de uma pessoa.

## Passos

### 1. Criar ou atualizar o checklist

Se `docs/seguranca.md` não existe, crie a partir de `checklist.md` desta pasta.
Se existe, releia: itens já marcados como não aplicáveis não voltam para a
auditoria.

Para cada item, o status é um destes — e nada além disso:

- **ok** — verificado, com a evidência escrita ao lado (arquivo e linha, ou
  print do comando que comprova)
- **falta** — não existe
- **parcial** — existe em parte; diga qual parte falta
- **não aplicável** — com o motivo escrito
- **não verificado** — ninguém olhou ainda. É o status inicial de tudo

Nunca marque **ok** sem evidência. Checklist com tudo verde e nenhuma evidência
é pior que checklist vazio: dá falsa confiança.

### 2. Auditar o código

Delegue ao agente `security`. Ele escreve em `reports/seguranca/`, separando o
que verificou do que não é verificável pelo repositório.

### 3. Auditar infra

Os itens de `infra.md` precisam de confirmação no ambiente real. O que der,
`devops` verifica; o que depender de acesso a console ou de decisão de custo,
**pergunte ao usuário** e registre a resposta dele como evidência.

Não presuma. "Provavelmente a hospedagem já faz backup" não é evidência.

### 4. Automatizar o que der

Item verificado por pessoa volta a quebrar. Item verificado pelo pipeline não.
Use `ci-seguranca.yml` desta pasta: varredura de segredo, auditoria de
dependência e análise estática, rodando em todo PR.

Esse passo vale mais que os três anteriores juntos. Priorize-o.

### 5. Fechar

Atualize o `docs/seguranca.md` com data, o que mudou de status, e o que ficou
pendente com dono. Crie um ADR quando a decisão for aceitar um risco
conscientemente — risco aceito e escrito é engenharia; risco esquecido é
acidente.

## O que de fato segura

Em ordem de eficácia real, de cima para baixo:

1. **Gate no pipeline** — varredura de segredo, auditoria de dependência,
   análise estática, e o build falhando quando acha algo
2. **Branch protection** — pipeline verde obrigatório, uma aprovação humana,
   sem push direto
3. **Configuração de plataforma** — HTTPS forçado, cofre de segredos, rede
   fechada, menor privilégio no IAM
4. **Hook local** — bloqueia o erro antes do commit
5. **Rule e checklist** — guiam quem escreve
6. **Revisão humana ou por agente** — pega o que o resto não pega

Os três primeiros são garantias: valem mesmo quando ninguém está prestando
atenção. Os três últimos são pedidos. Um projeto que só tem os últimos três não
tem segurança, tem boa intenção.

## Ameaça antes de código

A auditoria mais barata é a que acontece no desenho. Em `/sdlc-brainstorm`,
antes de existir código, responda:

- Que dado sensível esse sistema guarda? Se vazar, qual o estrago?
- Quem são os perfis de usuário e o que cada um **não** pode ver?
- Qual a superfície exposta na internet?
- Que parte, se cair, para o negócio?

Dez minutos ali evitam a refatoração de autorização que custa uma semana
depois.
