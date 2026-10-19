# Backlog

Fatias verticais. Cada uma atravessa a stack inteira e entrega algo observável.
Ordenadas por dependência: não pule.

Uma fatia por sessão, com `/sdlc-fatia`. Se uma não couber numa sessão, quebre.

## MVP

### F1 — Cadastro e login
Usuário, senha com argon2id, sessão com expiração, logout invalidando no
servidor. Toda rota daqui para frente escopada por `usuario_id`.
Inclui limite de tentativa de login na aplicação, por conta e não só por IP,
com atraso progressivo em vez de bloqueio (bloquear vira negação de serviço
contra o usuário legítimo). Cadastro fechado por `REGISTRO_ABERTO=false`
(padrão no `.env.example`, 404 quando desligado), usuário inicial por seed.
Se não couber numa sessão com o limite de tentativa, separar o limite em fatia
logo em seguida.
**MFA fica para a F20**: `mfa_segredo` existe no modelo-alvo
(`dados.md`), não na migration desta fatia.
Critérios extras: as rotas entram na spec gerada do código (ADR-019).
Relatório do QA inclui uma linha do que os testes **não** cobrem. Sem tela de
cadastro: com `REGISTRO_ABERTO=false` ela não teria uso. O seed usa a mesma
validação de e-mail e nome do cadastro (controle, tamanho).
Reimplementação em Java (ADR-016): a primeira versão, em Go, foi descartada
no PR da migração. Ficam as decisões (ADR-015) e a migration `V2`, já
aplicada. O frontend dela (login, logout, usuário logado) saiu junto, e está
no histórico em `a20d88b` e `dbc4018` para ser recuperado. Os relatórios de QA
e segurança daquela versão estão em `reports/` e os achados M2, M3, B3 e B4
continuam valendo para o desenho.
Requisitos novos da auditoria do PR da migração: Spring Security com
`Argon2PasswordEncoder` e `bcprov` (aprovados no ADR-015); usuário de banco do
Flyway separado do usuário da aplicação, que fica só com DML
(`spring.flyway.user`); `/v3/api-docs*` desligado fora de dev, ou os três paths
no teste de autorização; limite de tamanho do corpo JSON antes de abrir o
login; decidir CSRF com o cookie `SameSite=Lax` e conferir os cabeçalhos de
segurança que o Spring Security liga.

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

### F20.5 — Rate limit na borda
Limite por IP e por rota na entrada da API (infra). É esta fatia que destrava
`REGISTRO_ABERTO=true`: sem ela o endpoint de cadastro aberto não existe.
Tem de existir antes do primeiro deploy público, não só antes de abrir o
cadastro: o login sem limite de concorrência aloca 64 MiB de argon2 por
tentativa (auditoria de segurança da F1, M3), e o IP do log vem de
`RemoteAddr`, que atrás de proxy é o IP do proxy (B3). Inclui um semáforo no
argon2.
Também precisa conter o crescimento de `tentativa_login` (uma linha por e-mail
digitado, inclusive inexistente, sem limpeza hoje) e limpar sessões expiradas.

## Mais adiante

- **F26** — Importação de PDF de fatura (melhor esforço, revisão obrigatória)
- **F27** — Previsão de gasto e detecção de anomalia (serviço Python, ADR-007)
- **F28** — Classificação automática de categoria pela descrição
- **F29** — Capacitor para publicar nas lojas
- **DÍVIDA** — Fixar actions do GitHub por SHA, com Dependabot para atualizar. Tag é mutável: um comprometimento da action entraria no pipeline sem mudança de código nossa.
- **DÍVIDA** — Remover a tabela `health_check` e o `/healthz` que lê dela por migration nova, quando a F1 trouxer uma tabela real para o endpoint tocar.
- **DÍVIDA** — O Makefile faz `-include .env` com `export`, então todo alvo (inclusive `make test` e `make lint`) herda o `.env` inteiro, contra o menor privilégio do `seguranca.md`. Registrada em 2026-10-03, sem correção agendada; destrava quando um alvo precisar de segredo que os outros não devem ver. Com a api Java o `.env` tem `DB_PASSWORD` solto, e `make setup` (`pnpm install`) também o herda: um `postinstall` malicioso leria a senha (auditoria do PR da migração, B6).
- **DÍVIDA** — Gate do cliente TS não enxerga mudança de spec que o `openapi-typescript` não reflete nos tipos (`maxLength`, `description`, `example`), e passa sempre se `schema.d.ts` entrar no `.gitignore`. Aceito: o gate garante tipo em dia, não spec inteira propagada.
- **DÍVIDA** — `openapi-typescript` 7.13 declara peer `typescript ^5` e o web usa 6; só aparece em `pnpm peers check`. Reavaliar quando sair versão com suporte ao 6.
- **DÍVIDA** — `actionlint` não roda no CI nem localmente; o YAML dos workflows não é validado por ferramenta.
- **DÍVIDA** — `pnpm build` do web lê `../api/openapi.yaml`; se a hospedagem do PWA usar `web/` como raiz, o build falha. Conferir ao escolher a hospedagem (ou deixar a geração só no CI).
- **DÍVIDA** — `COOKIE_SECURE=false` em desenvolvimento (Safari recusa cookie `Secure` sobre HTTP). Servir o dev sobre HTTPS com certificado local (mkcert) e aposentar a flag. A F1 em Java precisa recusar subir com a flag falsa fora de `APP_ENV=development`, como a versão Go fazia.
- **DÍVIDA** — Sessão expirada ou revogada fica na tabela `sessao` sem limpeza. Resolver quando o volume importar ou na F20.5.
- **DÍVIDA** — E-mail e nome aceitam caracteres invisíveis da categoria Cf (U+200B, U+202E de inversão bidi, U+FEFF): dois e-mails que parecem iguais na tela e são diferentes, ou nome que embaralha a exibição. Decidir a regra (rejeitar Cf ou normalizar) na fatia que primeiro exibir nome de outra pessoa.
- **DÍVIDA** — Nenhuma verificação em navegador real: o fluxo login → `/auth/eu` → logout com o cookie de verdade, o layout em 360px e o foco só foram vistos em jsdom e por curl. Fazer à mão antes de a F2 começar.
- **DÍVIDA** — Atraso de login chaveado só pelo e-mail permite a um terceiro que conheça o e-mail da vítima mantê-la sob atraso (até 15 min) com uma requisição por janela (auditoria F1, M2). Sem bloqueio de conta, mas é negação de serviço parcial. Decisão de produto pendente: atraso por e-mail+IP, ou segundo fator/recuperação (F20). Revisar na F20.5.
- **DÍVIDA** — Endurecimentos baixos da auditoria da F1 (B4): cookie com prefixo `__Host-`, cabeçalho `X-Content-Type-Options: nosniff`, limpeza de sessões. Aceitos: B1 (409 do cadastro enumera e-mail só com `REGISTRO_ABERTO=true`) e B2 (`.env.example` de dev com `COOKIE_SECURE=false`; a guarda por `APP_ENV` cobre a cópia sem edição fora de dev).
- **DÍVIDA** — O check `análise estática (javascript-typescript)` tem nome de linguagem, não de módulo, e quebra na próxima troca de stack do web. Renomear para `análise estática (web)` com a mesma sequência do ruleset do ADR-016. Dono: devops, na próxima fatia que mexer no `seguranca.yml`.
- **DÍVIDA** — `/healthz` público consulta o banco e grava stack trace a cada falha: em flood esgota o pool e enche o log (auditoria do PR da migração, B2). Separar liveness (sem banco) de readiness (com banco) e limitar o log. Dono: backend, na F20.5.
- **DÍVIDA** — E-mail com letra de conversão exótica ("İ") pode virar minúscula diferente no `toLowerCase(Locale.ROOT)` do Java e no `lower()` do Postgres, e o CHECK de minúsculas daria 500. Mesmo risco da versão Go, sem teste. Resolver junto com a regra dos caracteres Cf.
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
