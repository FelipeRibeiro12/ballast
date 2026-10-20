# Backlog

Fatias verticais. Cada uma atravessa a stack inteira e entrega algo observável.
Ordenadas por dependência: não pule.

Uma fatia por sessão, com `/sdlc-fatia`. Se uma não couber numa sessão, quebre.

## MVP

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
Atenção: o confirmar com ajuste da F7 grava a `data` nas duas datas. Com
cartão, a prevista tem competência e caixa diferentes; confirmar com data tem
de mexer só na caixa, ou o gráfico por categoria erra (revisão do PR #12).

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
Inclui uma trava mecânica no deploy que recuse `REGISTRO_ABERTO=true` em
produção enquanto esta fatia não fechar: hoje só o comentário do
`.env.example` impede (auditoria do PR #6). Dono: devops.

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
- **DÍVIDA** — `COOKIE_SECURE=false` em desenvolvimento (Safari recusa cookie `Secure` sobre HTTP). Servir o dev sobre HTTPS com certificado local (mkcert) e aposentar a flag. A API já recusa subir com a flag falsa fora de `APP_ENV=development`, antes de migrar o banco.
- **DÍVIDA** — Sessão expirada ou revogada fica na tabela `sessao` sem limpeza. Resolver quando o volume importar ou na F20.5.
- **DÍVIDA** — E-mail e nome aceitam caracteres invisíveis da categoria Cf (U+200B, U+202E de inversão bidi, U+FEFF): dois e-mails que parecem iguais na tela e são diferentes, ou nome que embaralha a exibição. Decidir a regra (rejeitar Cf ou normalizar) na fatia que primeiro exibir nome de outra pessoa. Vale também para nome de conta (QA da F2, S1): NBSP sozinho vira nome, U+200B não colide com a versão sem ele.
- **DÍVIDA** — Verificação em navegador real só parcial: o dono entrou no web com o usuário do seed em 2026-10-19, mas o layout em 360px, o foco e o logout com o cookie de verdade ainda não foram conferidos à mão. Fazer junto com a tela de contas da F2.
- **DÍVIDA** — Atraso de login chaveado só pelo e-mail permite a um terceiro que conheça o e-mail da vítima mantê-la sob atraso (até 15 min) com uma requisição por janela (auditoria F1, M2). Sem bloqueio de conta, mas é negação de serviço parcial. Decisão de produto pendente: atraso por e-mail+IP, ou segundo fator/recuperação (F20). Revisar na F20.5.
- **DÍVIDA** — Endurecimentos baixos da auditoria da F1 (B4): cookie com prefixo `__Host-`, cabeçalho `X-Content-Type-Options: nosniff`, limpeza de sessões. Aceitos: B1 (409 do cadastro enumera e-mail só com `REGISTRO_ABERTO=true`) e B2 (`.env.example` de dev com `COOKIE_SECURE=false`; a guarda por `APP_ENV` cobre a cópia sem edição fora de dev).
- **DÍVIDA** — O check `análise estática (javascript-typescript)` tem nome de linguagem, não de módulo, e quebra na próxima troca de stack do web. Renomear para `análise estática (web)` com a mesma sequência do ruleset do ADR-016. Dono: devops, na próxima fatia que mexer no `seguranca.yml`.
- **DÍVIDA** — `/healthz` público consulta o banco e grava stack trace a cada falha: em flood esgota o pool e enche o log (auditoria do PR da migração, B2). Separar liveness (sem banco) de readiness (com banco) e limitar o log. Dono: backend, na F20.5.
- **DÍVIDA** — E-mail com letra de conversão exótica ("İ") pode virar minúscula diferente no `toLowerCase(Locale.ROOT)` do Java e no `lower()` do Postgres, e o CHECK de minúsculas daria 500. Mesmo risco da versão Go, sem teste. Resolver junto com a regra dos caracteres Cf. Na F2 o índice `lower(nome)` da conta deixa passar `İstambul` e `i̇stambul` como nomes diferentes (QA da F2, D3).
- **DÍVIDA** — Dois cookies `ballast_sessao` na mesma requisição contam como sem sessão, mesmo os dois válidos e iguais (`20ac315`). Decisão deliberada contra cookie tossing; manter o teste que trava isso.
- **DÍVIDA** — O `osv-scanner -L api/pom.xml` não resolve as versões herdadas do BOM do Boot para todos os pacotes; a cobertura do grafo transitivo é parcial (auditoria do PR #5). Gerar SBOM ou `dependency:tree` no CI e escanear o resultado. Dono: devops, junto com o Dependabot.
- **DÍVIDA** — `%2F` ou `{}` no caminho recebem a página de erro HTML do Tomcat, não `{"erro": ...}` (QA da F2, D5). Sem vazamento além de ser Tomcat. Dono: backend, na F20.5 junto com os cabeçalhos.
- **DÍVIDA** — Chave JSON repetida no corpo: a última vence sem aviso (QA da F2, S3). Ligar `STRICT_DUPLICATE_DETECTION` do Jackson e testar. Dono: backend, na próxima fatia com rota de escrita.
- **DÍVIDA** — O `HealthRepository` restaura o `setNetworkTimeout` no `finally` sem tolerar conexão já fechada pelo Hikari, o caso que dava 500 no `SessionQueries` (F2). Conferir com teste de banco congelado sem espera e unificar os dois num utilitário. Dono: backend, na F20.5 junto com liveness e readiness.
- **DÍVIDA** — Sem teto de contas por usuário e sem paginação em `GET /contas` (auditoria do PR #7, B1). Só afeta a própria conta. Dono: backend, na F20.5 junto com o rate limit. O mesmo vale para `GET /lancamentos`, cujo teto é só a janela de 366 dias (revisão do PR #8); paginar na fatia que trouxer importação. Vale também para recorrências: sem teto de templates ativos por usuário, cada diária grava ~366 previstas e o job refaz todas por dia (auditoria do PR #10, M1).
- **DÍVIDA** — O teto de 2 s da consulta de sessão (`SessionQueries`) só tem teste de banco congelado. Falta um que confira que o `networkTimeout` da conexão volta ao valor do pool depois de uma consulta normal (revisão do PR #7). Dono: backend, na fatia que mexer no pool.
- **DÍVIDA** — Categoria filha pode ter tipo diferente do pai: a V6 garante um nível só, mas não o mesmo tipo (F3). Um `CHECK` junto com a FK do pai fecha isso. Dono: dba, na fatia que trouxer o CRUD de categoria.
- **DÍVIDA** — Sem idempotência na escrita de dinheiro: dois `POST /transferencias` (ou `/lancamentos`) idênticos gravam duas vezes (QA da F4, S2). O web barra o submit duplo, mas rede instável com retentativa não. Chave de idempotência por requisição. Dono: backend, antes do PWA offline ou da importação.
- **DÍVIDA** — `POST /lancamentos` com `tipo: transferencia` é recusado com "categoriaId é obrigatório" ou "requisição inválida", não com uma mensagem que aponte para `/transferencias` (QA da F4, S1). Dono: backend, na próxima fatia que mexer em `EntryController`.
- **DÍVIDA** — Deadlock (SQLState 40P01) responde 500, não 503 com "tente de novo" (F5). A ordem das travas do ADR-026 não forma ciclo hoje, então só apareceria com caminho novo fora da ordem. Tratar 40P01 junto com o 55P03 do `lock_timeout`. Dono: backend, na próxima fatia que mexer em `ApiExceptionHandler`.
- **DÍVIDA** — Cancelar uma ocorrência de template e depois apagá-la faz a data voltar como prevista se for de hoje em diante (QA da F5, S2). É o contrato do ADR-026, mas surpreende. Decidir se apagar cancelada some de vez (manter a linha oculta) quando houver pedido. Dono: tech-lead, com o dono do produto.
- **DÍVIDA** — O job de recorrência só loga pulo e falha; não loga quantas previstas inseriu por template, e uma falha de banco aborta a rodada sem resumo (QA da F5, S4). Dono: backend, junto com a observabilidade da F20.5.
- **DÍVIDA** — O job de recorrência não trava a conta (ADR-026): numa corrida com arquivar, pode sobrar prevista em conta arquivada. Não entra no saldo e não confirma, mas aparece na lista. Rever se arquivar passar a apagar as previstas da conta. Dono: backend, na fatia que mexer em arquivar conta.
- **DÍVIDA** — `RecurrenceQueries.lockTemplate` monta o template com a divisão nula e completa com uma segunda consulta; um caminho novo que use o objeto antes disso materializaria a série sem divisão (revisão do PR #11). Juntar numa leitura só. Dono: backend, na próxima fatia que mexer em recorrência.
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
