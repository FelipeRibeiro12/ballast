# Backlog

Fatias verticais. Cada uma atravessa a stack inteira e entrega algo observável.
Ordenadas por dependência: não pule.

Uma fatia por sessão, com `/sdlc-fatia`. Se uma não couber numa sessão, quebre.

## MVP

**Fim do MVP.** A partir daqui, cada fatia é opcional e pode ser reordenada.

## Depois

### F21 — Alerta de conta a vencer
Notificação no PWA. No iOS só funciona se instalado na tela inicial.

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
Movimento sincronizado chega sem custo: o custo médio da posição fica nulo e a
tela pede o custo em vez de inventar (ADR-042).

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
- **DÍVIDA** — O web ainda não usa o que a API de renda fixa e cripto ganhou no ADR-058: desfazer resgate (`DELETE /titulos-renda-fixa/{id}/resgate`), `vencido`, `avisoIndice` e `motivoSemValor` do título, `rendaFixaSemValor` do patrimônio, e editar ou apagar ativo e cotação. Dono: frontend.
- **DÍVIDA** — Movimento em dólar gravado antes da primeira PTAX volta com `custoCentavos` nulo até a busca em segundo plano terminar (ADR-058). Dono: frontend, mostrar "calculando" em vez de vazio.
- **DÍVIDA** — A V25 pode derrubar a migration se duas filhas de pais diferentes, com tipo divergente, tiverem o mesmo nome: o rename para raiz colide com `categoria_pai_nome_unico` (revisão da onda 5). Só com dado que nenhuma rota cria; desempatar com o id no sufixo antes do CRUD de categoria. Dono: dba.
- **DÍVIDA** — O CRUD de categoria precisa proteger as categorias com `semente` (renomear e apagar), porque Posso gastar e apagar conta dependem delas (ADR-057). Dono: backend, na fatia do CRUD de categoria.
- **DÍVIDA** — A V26 falharia num banco que já tivesse saída de transferência em conta de cartão; a aplicação nunca gravou isso, mas conferir os dados antes de migrar uma produção com histórico (ADR-057). Dono: dba.
- **DÍVIDA** — A espera por chave de idempotência em andamento segura uma thread do Tomcat por até 10 s, e uma linha órfã só é assumida após 60 s (auditoria da onda 4b, B1). Reduzir a espera para 2 a 3 s ou limitar os waiters. Dono: backend.
- **DÍVIDA** — O replay de idempotência não reenvia `Location`, e o web não tem mensagem própria para 422 e 409 com `Retry-After` (onda 4b). Dono: backend e frontend.
- **DÍVIDA** — A resposta 2xx guardada por 24 h na tabela `idempotencia` fica em texto puro; conferir que nenhum DTO das rotas com chave leva dado sensível e que o backup é cifrado (auditoria da onda 4b, B2). Dono: backend e devops.
- **DÍVIDA** — Apagar uma ocorrência `realizado` ainda libera a chave e a data volta como prevista; só a cancelada deixa marca em `ocorrencia_apagada` (ADR-054). Rever se surpreender. Dono: backend.
- **DÍVIDA** — `DELETE /contas/{id}` confere o código do MFA antes de validar a conta: um 404 ou 400 de regra gasta um código de recuperação (auditoria da onda 2, M1). Validar o dono primeiro e responder 404 igual. Dono: backend.
- **DÍVIDA** — O atraso por e-mail+IP cria uma linha em `tentativa_login` por par; confirmar que a limpeza diária cobre e que o e-mail tem limite de tamanho (auditoria da onda 2, M2). Em produção, conferir com o devops que o IP só vem do Caddy com o segredo do Vercel. Dono: backend e devops.
- **DÍVIDA** — O atraso do MFA é por usuário e compartilhado entre login, apagar conta e renovar códigos: sessão roubada pode travar o login MFA do dono (auditoria da onda 2, B4). Aceito por ora. Dono: backend.
- **DÍVIDA** — Imagens de produção por tag (`eclipse-temurin:25-jre-noble`, `caddy:2.10`, `postgres:17`): fixar por digest com atualização automática, junto com a dívida das actions abaixo. Dono: devops.
- **DÍVIDA** — Navegação com seções fora da barra: Orçamento, Metas e Relatórios só abrem pelo Início e pelos atalhos de planejamento (ADR-043, ADR-045, ADR-046). A próxima seção pede outro desenho (menu "Mais" ou rótulos curtos), com teste em 360px. Dono: frontend.
- **DÍVIDA** — O limite por IP vive na memória da única instância da API (ADR-049). Uma segunda instância contaria a própria janela. Estado compartilhado, com ADR, na fatia que escalar a API. Dono: backend.
- **DÍVIDA** — Fixar actions do GitHub por SHA, com Dependabot para atualizar. Tag é mutável: um comprometimento da action entraria no pipeline sem mudança de código nossa.
- **DÍVIDA** — Remover a tabela `health_check` e o `/healthz` que lê dela por migration nova, quando a F1 trouxer uma tabela real para o endpoint tocar.
- **DÍVIDA** — Gate do cliente TS não enxerga mudança de spec que o `openapi-typescript` não reflete nos tipos (`maxLength`, `description`, `example`), e passa sempre se `schema.d.ts` entrar no `.gitignore`. Aceito: o gate garante tipo em dia, não spec inteira propagada.
- **DÍVIDA** — `openapi-typescript` 7.13 declara peer `typescript ^5` e o web usa 6; só aparece em `pnpm peers check`. Reavaliar quando sair versão com suporte ao 6.
- **DÍVIDA** — `actionlint` não roda no CI nem localmente; o YAML dos workflows não é validado por ferramenta.
- **DÍVIDA** — `pnpm build` do web lê `../api/openapi.yaml`; se a hospedagem do PWA usar `web/` como raiz, o build falha. Conferir ao escolher a hospedagem (ou deixar a geração só no CI).
- **DÍVIDA** — `COOKIE_SECURE=false` em desenvolvimento (Safari recusa cookie `Secure` sobre HTTP). Servir o dev sobre HTTPS com certificado local (mkcert) e aposentar a flag. A API já recusa subir com a flag falsa fora de `APP_ENV=development`, antes de migrar o banco.
- **DÍVIDA** — O job `e2e` do CI ainda não é obrigatório no ruleset (ADR-033). Torná-lo obrigatório depois de cinco execuções seguidas sem falha intermitente. Dono: devops.
- **DÍVIDA** — O check `análise estática (javascript-typescript)` tem nome de linguagem, não de módulo, e quebra na próxima troca de stack do web. Renomear para `análise estática (web)` com a mesma sequência do ruleset do ADR-016. Dono: devops, na próxima fatia que mexer no `seguranca.yml`.
- **DÍVIDA** — `/healthz` público consulta o banco. Desde a F20.5 ele faz uma consulta por vez e loga um stack trace por minuto (ADR-049), mas liveness (sem banco) e readiness (com banco) seguem juntos (auditoria do PR da migração, B2). Separar quando houver orquestrador que use os dois. Dono: backend.
- **DÍVIDA** — Dois cookies `ballast_sessao` na mesma requisição contam como sem sessão, mesmo os dois válidos e iguais (`20ac315`). Decisão deliberada contra cookie tossing; manter o teste que trava isso.
- **DÍVIDA** — O `osv-scanner -L api/pom.xml` não resolve as versões herdadas do BOM do Boot para todos os pacotes; a cobertura do grafo transitivo é parcial (auditoria do PR #5). Gerar SBOM ou `dependency:tree` no CI e escanear o resultado. Dono: devops, junto com o Dependabot.
- **DÍVIDA** — O `HealthRepository` restaura o `setNetworkTimeout` no `finally` sem tolerar conexão já fechada pelo Hikari, o caso que dava 500 no `SessionQueries` (F2). Conferir com teste de banco congelado sem espera e unificar os dois num utilitário. Dono: backend, junto com liveness e readiness.
- **DÍVIDA** — O teto de 2 s da consulta de sessão (`SessionQueries`) só tem teste de banco congelado. Falta um que confira que o `networkTimeout` da conexão volta ao valor do pool depois de uma consulta normal (revisão do PR #7). Dono: backend, na fatia que mexer no pool.
- **DÍVIDA** — O job de recorrência só loga pulo e falha; não loga quantas previstas inseriu por template, e uma falha de banco aborta a rodada sem resumo (QA da F5, S4). Dono: backend, numa fatia de observabilidade.
- **DÍVIDA** — O saldo projetado e o saldo de `/contas` leem todo o histórico de lançamentos do usuário a cada chamada, porque o realizado vale em qualquer data (revisão e auditoria do PR #13). Hoje ~21 ms com 20 mil lançamentos. Medir com anos de histórico e, se doer, saldo base materializado com ADR (CLAUDE.md, "Saldo não é coluna") ou índice por `(usuario_id, conta_id, data_caixa)`. O `GET /patrimonio/mensal` faz essa leitura doze vezes (ADR-047). Dono: dba, quando o volume real passar de 100 mil lançamentos por usuário.
- **DÍVIDA** — `GET /lancamentos` filtra só por `data_caixa`, então a compra no cartão aparece no mês do vencimento (F10). Falta ver por competência ("o que comprei no mês"), com um parâmetro como `por=competencia` e o índice correspondente medido. Os Relatórios (ADR-046) já somam por competência, mas a lista ainda não filtra por ela. Dono: backend e frontend, quando houver pedido de ver a lista por competência.
- **DÍVIDA** — Os `isConstraint` antigos de `EntryService` e `InstallmentService` repetem o que `web/Constraints.isViolated` faz (F12). Trocar todos pelo helper num refator próprio. Dono: backend, na próxima fatia que mexer em `entries`.
- **DÍVIDA** — Recorrência cuja categoria saiu da lista de um benefício é pulada pelo job em toda rodada, com warn, até a lista voltar (F12). Desde a F13.1 o usuário troca a categoria pelo editar; falta avisar na tela que a recorrência parou. Dono: frontend.
- **DÍVIDA** — `isCategoryPermitted` repetida em `EntryQueries` e `RecurrenceQueries` (revisão do PR #19). Juntar num lugar só no refator dos helpers de constraint. Dono: backend.
- **DÍVIDA** — Arquivar uma conta cripto tira a posição dela do patrimônio sem aviso na tela (revisão do PR #20). É o contrato ("só contas ativas"), mas a queda no total confunde. Avisar na aba Cripto quando houver conta cripto arquivada com posição. Dono: frontend.
- **DÍVIDA** — `isCryptoAccount` repetida em `EntryQueries` e `RecurrenceQueries` (revisão do PR #20), como a `isCategoryPermitted`. Juntar no mesmo refator dos helpers. Dono: backend.
- **DÍVIDA** — Trocar a divisão de salário de uma recorrência depois de uma parcela realizada no mês deixa a soma do mês diferente do total (R$ 1.000 em 2 partes com a 1ª realizada, trocado para 3 partes: o mês soma R$ 1.166,70) (QA da F13.1, D2). Coerente com "realizada fica", mas nada avisa. Avisar no formulário de edição ou regerar o mês corrente inteiro quando a divisão muda. Dono: tech-lead com o dono do produto.
- **DÍVIDA** — `conta.aceita_lancamento` e a FK `lancamento_conta_aceita_lancamento` da V13 ficaram redundantes com `recebe_lancamento` da V14 (ADR-037). Remover é DROP de coluna: pare e avise o dono antes. Dono: dba.
- **DÍVIDA** — O IPCA do título usa o IPCA do mês (ou o último publicado) com pro rata pelos dias corridos, sem a projeção nem o aniversário que o banco usa; a marcação corrige (ADR-037). Rever se o desvio incomodar. Dono: backend.
- **DÍVIDA** — O e2e e o CI sobem a API com o job de índices ligado, que consulta o Banco Central de verdade ao subir. O fluxo não depende do resultado (título aplicado no dia vale o aplicado), mas é rede externa num teste. Desligar no `scripts/e2e.sh` ou usar uma fonte local. Dono: devops.
- **DÍVIDA** — "Ver posições" da conta cripto e o link antigo `#/cripto` abrem Investimentos na aba Renda fixa (revisão do PR #22). Aceitar a aba na rota, como `#/investimentos/cripto`. Dono: frontend.
- **DÍVIDA** — O web usa `CriptoNaoEncontradoError` e `recusarRespostaDeCripto` também para título de renda fixa (revisão do PR #22). Renomear para um erro de investimento. Dono: frontend.
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
- **DÍVIDA** — Terceiros: criar o lançamento e ligar ao terceiro são duas chamadas do web (ADR-062). Uma falha no meio deixa o lançamento sem vínculo. Aceitar `terceiroId` no `POST /lancamentos` e no `POST /compras-parceladas` fecha a janela. Dono: backend.
- **DÍVIDA** — Dia útil só conhece feriado nacional (ADR-060). Feriado estadual e municipal mudam o pagamento real da fatura. Dono: backend, se o dono pedir.
- **DÍVIDA** — Orçamento ao apagar categoria soma o teto na Outros mês a mês, sem recalcular a vigência (ADR-064). Dono: backend.

- **DÍVIDA** — Apagar categoria só soma o teto da Outros quando o mês de vigência coincide, e descarta a linha com teto nulo (auditoria da rodada pós uso real, M1). Somar pela linha vigente de cada mês em que uma das duas muda. Dono: backend.
- **DÍVIDA** — Cada `PUT /contas/{id}/cofrinho` pede a busca do CDI ao Banco Central, sem intervalo mínimo (auditoria, B2). Dono: backend.
- **DÍVIDA** — Importação de OFX: pendentes esquecidas ficam para sempre e a `bruta` das confirmadas nunca é limpa (ADR-069, auditoria da F22, B3). Apagar pendente por idade e a `bruta` de confirmada antiga. Dono: backend. Fatia: rodada de dívidas após a F22.
- **DÍVIDA** — Sem teto de lançamentos por usuário: uma importação de 2000 linhas por arquivo, repetida, enche a tabela (auditoria da F22, B1). Definir o teto no padrão do ADR-055. Dono: backend. Fatia: rodada de dívidas após a F22.
