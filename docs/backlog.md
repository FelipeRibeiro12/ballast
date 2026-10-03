# Backlog

Fatias verticais. Cada uma atravessa a stack inteira e entrega algo observável.
Ordenadas por dependência: não pule.

Uma fatia por sessão, com `/sdlc-fatia`. Se uma não couber numa sessão, quebre.

## MVP

### F0 — Walking skeleton
Repositório, CI verde, `GET /healthz` tocando o banco, tela que mostra o
resultado. Nada de domínio. Use `/bootstrap` e depois `/sdlc-skeleton`.

### F1 — Cadastro e login
Usuário, senha com argon2id, sessão com expiração, logout invalidando no
servidor. Toda rota daqui para frente escopada por `usuario_id`.
**MFA fica para a F20** — mas a coluna já existe no modelo.

### F2 — Contas
Criar, listar, arquivar. Tipos `corrente`, `poupanca`, `dinheiro`.
Saldo calculado (`saldo_inicial` + lançamentos), nunca materializado.

### F3 — Lançamento realizado
Receita e despesa com título, valor, categoria, descrição opcional, conta e
data. Saldo da conta reagindo. Categorias semeadas.
Aqui as duas datas já existem e são iguais — não adie isso.

### F4 — Transferência entre contas
Par de lançamentos ligados, excluído de todo relatório de receita e despesa.
É curta, mas se vier depois dos gráficos você refaz os gráficos.

### F5 — Recorrência
A fatia mais difícil do projeto. Regra, template, materialização 12 meses à
frente, idempotente.
Teste obrigatório: dia 31 em fevereiro, ano bissexto, intervalo maior que 1,
rodar a materialização duas vezes.

### F6 — Divisão de salário
`template_divisao` por percentual e por valor fixo, com mais de dois dias.
Teste obrigatório: a regra do centavo — três partes de 33,33% de R$ 1.000
precisam somar exatamente R$ 1.000.

### F7 — Previsto × realizado
Marcar um previsto como realizado (ajustando valor e data se vier diferente).
Editar uma ocorrência marca `desgarrado` e a materialização passa a respeitar.

### F8 — Saldo projetado
Fluxo de caixa dos próximos 90 dias por `data_caixa`. É o primeiro momento em
que o app responde algo que planilha não responde fácil.

**Fim do MVP.** A partir daqui, cada fatia é opcional e pode ser reordenada.

## Depois

### F9 — PWA instalável
Manifest, ícones e service worker via vite-plugin-pwa com `devOptions.enabled:
false`. Service worker em desenvolvimento atrapalha por cachear assets; vale
quando há algo para instalar no celular. Pronto: instalável no Android e iOS,
`make dev` sem cache de asset.

### F10 — Cartão de crédito
Conta tipo cartão, fechamento e vencimento, cálculo de `data_caixa`, fatura
agrupada, pagamento como transferência.
Teste obrigatório: fechamento dia 31 em fevereiro, vencimento menor que
fechamento, compra no dia do fechamento.

### F11 — Compra parcelada
N lançamentos ligados por `parcela_de_id`, mesma competência, caixa avançando.
Editar ou cancelar a compra inteira versus uma parcela só.

### F12 — Benefícios com restrição
Conta tipo benefício, categorias permitidas, validação na aplicação e por
trigger. Saldo do VR separado no painel.

### F13 — Cripto manual
Ativo identificado por `rede` + `contrato`, posição em quantidade,
movimentação com custo de aquisição informado por você.
Patrimônio total passa a receber data de referência.

### F14 — Cotação automática
Worker com ticker, série histórica, cache da última cotação conhecida, data da
cotação visível na interface.
Teste obrigatório: API fora do ar não pode travar lançamento.

### F15 — Aporte × valorização
Quanto do crescimento foi aporte e quanto foi preço. Sem isso, cripto subindo
parece economia.
Depende de `preco_unitario` nas movimentações — e ele é nullable, então a tela
precisa pedir o custo quando faltar.

### F16 — Orçamento por categoria
Teto mensal, consumo por `data_competencia`, quanto falta.

### F17 — Metas
Criar, aporte recorrente, progresso, e "no ritmo atual chego em tal data".

### F18 — Gráficos
Gasto por categoria, receita versus despesa no mês, patrimônio no tempo,
consumo de orçamento. Mobile-first: tem que ser legível em 360px.

### F19 — Posso gastar quanto
Saldo menos contas a vencer até o próximo salário, respeitando a restrição dos
benefícios. A pergunta que faz o app ser aberto todo dia.

### F20 — MFA
TOTP, segredo cifrado, código de recuperação em hash, limite de tentativa.
Antes de publicar para outras pessoas, isso deixa de ser opcional.

### F21 — Alerta de conta a vencer
Notificação no PWA. No iOS só funciona se instalado na tela inicial.

### F22 — Importação de OFX
Envio de arquivo, parse, linhas cruas, proposta de decisão por linha, tela de
revisão, conciliação contra lançamento manual (ADR-008).
A fatia mais valiosa depois do MVP: elimina digitação.
Teste obrigatório: importar o mesmo arquivo duas vezes não duplica nada;
extratos com dias sobrepostos não duplicam; conciliação não cria lançamento
novo.

### F23 — Importação de CSV
Mapeamento de coluna salvável por banco, sobre a mesma máquina de revisão da
F22. Entrada não confiável: limite de tamanho, limite de linhas, tratamento de
fórmula injetada.

### F24 — Observar endereço de carteira
Cadastro de endereço digitado, por rede. Sincronização de saldo via
indexador, `confiavel = false` por padrão para ativo desconhecido, data da
última leitura visível (ADR-009).
Redes do MVP: Ethereum, Base, Solana.
Teste obrigatório: indexador fora do ar mostra saldo antigo com a data, não
erro nem zero; token de spam não entra no patrimônio.

### F25 — Conectar carteira
Botão que preenche o endereço automaticamente. EVM com `wagmi` v2 e descoberta
por **EIP-6963** — Rabby aparece na lista junto com as outras. Solana por
**Wallet Standard** — Phantom aparece na lista. Rabby e Phantom em primeiro na
ordem de exibição.
Assinatura opcional de posse (SIWE). **Nunca** assinatura de transação.
Teste obrigatório: o fluxo de digitar o endereço continua funcionando sem
nenhuma extensão instalada.

## Mais adiante

- **F26** — Importação de PDF de fatura (melhor esforço, revisão obrigatória)
- **F27** — Previsão de gasto e detecção de anomalia (serviço Python, ADR-007)
- **F28** — Classificação automática de categoria pela descrição
- **F29** — Capacitor para publicar nas lojas
- **DÍVIDA** — Fixar actions do GitHub por SHA, com Dependabot para atualizar. Tag é mutável: um comprometimento da action entraria no pipeline sem mudança de código nossa.
- **DÍVIDA** — Tipo `Healthz` escrito à mão em `web/src/api.ts` (contra `.claude/rules/typescript.md`). Sai quando o OpenAPI e o cliente TS gerado existirem; a primeira fatia com rota real cria os dois.
- **DÍVIDA** — Remover a tabela `health_check` e o `/healthz` que lê dela por migration nova, quando a F1 trouxer uma tabela real para o endpoint tocar.
- **DÍVIDA** — Revisar a imagem do runner — fixada em ubuntu-24.04 em 2026-10-03; conferir migração para a 26 depois de 2026-11.

## Fora de escopo

Open Finance, conciliação contábil, imposto, nota fiscal, divisão de despesa
entre pessoas, saldo automático de corretora. Motivos em `visao.md`.

---

## Regras do backlog

- Fatia entregue não volta ao backlog: vira linha no changelog.
- Fatia que cresceu durante a implementação é sinal de que estava mal cortada.
  Anote o que sobrou como fatia nova em vez de esticar a atual.
- Dívida técnica aceita vira linha aqui com dono, não comentário `TODO` no
  código.
