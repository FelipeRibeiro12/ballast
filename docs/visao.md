# Visão — Ballast

## O problema

Dinheiro espalhado em lugares que não conversam: conta corrente, VR, VA,
carteira de cripto, dinheiro vivo. Cada um com regra própria — o saldo do VR só
paga comida, o cripto só vale o que a cotação diz hoje, o cartão cobra em
novembro o que foi gasto em outubro.

Planilha resolve o registro, mas não responde a pergunta que importa: **quanto
eu posso gastar hoje sem furar o mês?**

## Para quem

Uma pessoa com renda parcelada no mês, benefícios de uso restrito, gastos
recorrentes e alguma posição em cripto. Começa com um usuário (eu), mas o
modelo nasce multiusuário porque a intenção é publicar.

Multiusuário não é multi-pessoa: cada um vê só o seu. Conta compartilhada e
divisão de despesa entre pessoas ficam fora do escopo inicial.

## O que torna isso difícil

Quatro coisas, e nenhuma delas é CRUD:

1. **Recorrência de verdade.** Salário em dois dias do mês com percentual
   diferente em cada. Despesa recorrente que num mês veio diferente e precisa
   ser editada sem bagunçar a série.
2. **Dinheiro não é fungível.** VR não paga combustível. Saldo total sem regra
   é um número que mente.
3. **Cripto não tem saldo, tem quantidade.** O valor é derivado da cotação, e
   muda sozinho.
4. **Competência ≠ caixa.** Comprei dia 3 de outubro, o dinheiro sai dia 10 de
   novembro. Gráfico de gasto por categoria usa a data da compra; saldo
   projetado usa a data do pagamento.

A quarta é a que decide o modelo e por isso está resolvida desde a primeira
migration, não depois.

## MVP

A menor coisa que já responde "quanto eu tenho e quanto posso gastar":

- Cadastro e login
- Contas de tipos diferentes, com saldo
- Lançamento de receita e despesa, com categoria, descrição e conta
- Transferência entre contas
- Recorrência, incluindo divisão de salário por dia do mês
- Previsto × realizado, e saldo projetado para os próximos 90 dias

Isso é o núcleo. Sem ele, nada mais faz sentido.

## Depois do MVP, em ordem

1. Cartão de crédito com fatura e parcelamento
2. Benefícios com restrição de categoria (VR, VA)
3. Cripto: posição, cotação automática, aporte × valorização
4. Orçamento por categoria no mês
5. Metas financeiras com aporte recorrente
6. Gráficos
7. "Posso gastar quanto?"
8. Alerta de conta a vencer
9. Importação de extrato em OFX, com conciliação
10. Observar endereço de carteira, com saldo sincronizado da rede
11. Conectar Rabby (EVM) e Phantom (Solana) para preencher o endereço
12. Serviço de previsão e anomalia em Python

## Fora de escopo, e por quê

- **Open Finance / integração bancária automática** — exige ser instituição
  regulada ou contratar agregador pago. Importação de OFX/CSV cobre 80% da
  dor por 2% do custo.
- **Scraping de internet banking** — quebra sem aviso, viola termo de uso e
  exigiria guardar credencial bancária. Descartado por segurança.
- **Custódia de cripto** — o app observa endereço, nunca guarda chave nem
  assina transação. Não é carteira (ADR-009).
- **Saldo automático de corretora** — cotação automática sim, posição não.
- **App nativo em loja** — PWA instalável resolve Android, desktop e web com um
  código. Capacitor entra se a loja virar necessidade.
- **Divisão de despesa entre pessoas** — produto diferente, modelo diferente.
- **Conciliação contábil, nota fiscal, imposto** — não é um ERP.
- **Corretora conectada** — cotação automática sim, saldo automático não.

## Decisões tomadas

| Assunto | Decisão | ADR |
|---|---|---|
| Forma do sistema | Monolito modular | 001 |
| Backend | Go | 002 |
| Banco | Postgres | 003 |
| Clientes | PWA React, mobile-first | 004 |
| Dinheiro | Inteiro em centavos; cripto em decimal | 005 |
| Cripto | Quantidade armazenada, valor derivado | 006 |
| Análise em Python | Só depois do MVP, somente leitura | 007 |
| Importação de extrato | OFX primeiro, com revisão obrigatória | 008 |
| Carteiras de cripto | Observar endereço; nunca custodiar nem assinar | 009 |

## O desenho

```
┌──────────────────────────────┐
│  PWA React (mobile-first)    │
└──────────────┬───────────────┘
               │ HTTP + OpenAPI
┌──────────────▼───────────────┐
│  API Go — monolito modular   │
│                              │
│  contas · lancamentos        │
│  recorrencia · cartao        │
│  metas · orcamento           │
│  cotacoes (worker interno)   │
└──────────────┬───────────────┘
               │
        ┌──────▼──────┐     ┌─────────────────┐
        │  Postgres   │     │ previsao (py)   │
        └─────────────┘     │ fatia 27, leitura│
                            └─────────────────┘
```

Caixas por domínio, não por camada. O worker de cotação é uma goroutine com
ticker dentro do monolito, não um serviço.
