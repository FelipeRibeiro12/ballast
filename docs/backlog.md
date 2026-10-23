# Backlog

Fatias verticais. Cada uma atravessa a stack inteira e entrega algo observável.
Ordenadas por dependência: não pule.

Uma fatia por sessão, com `/sdlc-fatia`. Se uma não couber numa sessão, quebre.

## MVP

**Fim do MVP.** A partir daqui, cada fatia é opcional e pode ser reordenada.

## Depois

### F14 — Cotação automática
Worker com ticker, série histórica, cache da última cotação conhecida, data da
cotação visível na interface.
Teste obrigatório: API fora do ar não pode travar lançamento.

### F15 — Aporte × valorização
Quanto do crescimento foi aporte e quanto foi preço. Sem isso, cripto subindo
parece economia.
Inclui o custo médio da posição de cripto: hoje `custoCentavos` é compras menos
vendas e a tela o chama assim (QA da F13, D2).
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
- **DÍVIDA** — O job `e2e` do CI ainda não é obrigatório no ruleset (ADR-033). Torná-lo obrigatório depois de cinco execuções seguidas sem falha intermitente. Dono: devops.
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
- **DÍVIDA** — Sem teto de contas por usuário e sem paginação em `GET /contas` (auditoria do PR #7, B1). Só afeta a própria conta. Dono: backend, na F20.5 junto com o rate limit. O mesmo vale para `GET /lancamentos`, cujo teto é só a janela de 366 dias (revisão do PR #8); paginar na fatia que trouxer importação. Vale também para recorrências: sem teto de templates ativos por usuário, cada diária grava ~366 previstas e o job refaz todas por dia (auditoria do PR #10, M1). A compra parcelada multiplica por 48: cada POST grava até 48 linhas segurando a trava da conta (auditoria do PR #17, M1).
- **DÍVIDA** — O teto de 2 s da consulta de sessão (`SessionQueries`) só tem teste de banco congelado. Falta um que confira que o `networkTimeout` da conexão volta ao valor do pool depois de uma consulta normal (revisão do PR #7). Dono: backend, na fatia que mexer no pool.
- **DÍVIDA** — Categoria filha pode ter tipo diferente do pai: a V6 garante um nível só, mas não o mesmo tipo (F3). Um `CHECK` junto com a FK do pai fecha isso. Dono: dba, na fatia que trouxer o CRUD de categoria.
- **DÍVIDA** — Sem idempotência na escrita de dinheiro: dois `POST /transferencias` (ou `/lancamentos`) idênticos gravam duas vezes (QA da F4, S2). O web barra o submit duplo, mas rede instável com retentativa não. Chave de idempotência por requisição. Dono: backend, antes do PWA offline ou da importação.
- **DÍVIDA** — `POST /lancamentos` com `tipo: transferencia` é recusado com "categoriaId é obrigatório" ou "requisição inválida", não com uma mensagem que aponte para `/transferencias` (QA da F4, S1). Dono: backend, na próxima fatia que mexer em `EntryController`.
- **DÍVIDA** — Cancelar uma ocorrência de template e depois apagá-la faz a data voltar como prevista se for de hoje em diante (QA da F5, S2). É o contrato do ADR-026, mas surpreende. Decidir se apagar cancelada some de vez (manter a linha oculta) quando houver pedido. Dono: tech-lead, com o dono do produto.
- **DÍVIDA** — O job de recorrência só loga pulo e falha; não loga quantas previstas inseriu por template, e uma falha de banco aborta a rodada sem resumo (QA da F5, S4). Dono: backend, junto com a observabilidade da F20.5.
- **DÍVIDA** — O job de recorrência não trava a conta (ADR-026): numa corrida com arquivar, pode sobrar prevista em conta arquivada. Não entra no saldo e não confirma, mas aparece na lista. Rever se arquivar passar a apagar as previstas da conta. Dono: backend, na fatia que mexer em arquivar conta.
- **DÍVIDA** — `RecurrenceQueries.lockTemplate` monta o template com a divisão nula e completa com uma segunda consulta; um caminho novo que use o objeto antes disso materializaria a série sem divisão (revisão do PR #11). Juntar numa leitura só. Dono: backend, na próxima fatia que mexer em recorrência.
- **DÍVIDA** — Parâmetro de query não declarado é ignorado em silêncio em todas as listagens: `?contaid=` (caixa errada) devolve o resultado sem filtro, com 200 (QA da F8, S1). Recusar parâmetro desconhecido com 400, como já se faz com campo de corpo. Dono: backend, numa fatia de endurecimento ou na F20.5.
- **DÍVIDA** — O saldo projetado e o saldo de `/contas` leem todo o histórico de lançamentos do usuário a cada chamada, porque o realizado vale em qualquer data (revisão e auditoria do PR #13). Hoje ~21 ms com 20 mil lançamentos. Medir com anos de histórico e, se doer, saldo base materializado com ADR (CLAUDE.md, "Saldo não é coluna") ou índice por `(usuario_id, conta_id, data_caixa)`. Dono: dba, quando o volume real passar de 100 mil lançamentos por usuário.
- **DÍVIDA** — Na hospedagem do PWA, servir `/sw.js` com `Cache-Control: no-cache` (e HTTPS, sem o qual o service worker não registra); senão uma versão velha do worker demora a ser trocada (auditoria do PR #15). Dono: devops, na escolha da hospedagem.
- **DÍVIDA** — `GET /lancamentos` filtra só por `data_caixa`, então a compra no cartão aparece no mês do vencimento (F10). Falta ver por competência ("o que comprei no mês"), com um parâmetro como `por=competencia` e o índice correspondente medido. Dono: backend e frontend, na fatia de gráfico por categoria, que usa competência.
- **DÍVIDA** — "Cartão nunca é origem de transferência" é só regra da aplicação; o banco aceitaria uma perna de saída em cartão (auditoria do PR #16). Garantir por FK composta com o tipo, como a V10 fez para a conta de pagamento. Dono: dba, na próxima migration que mexer em transferência.
- **DÍVIDA** — `PATCH /compras-parceladas/{id}` não distingue `descricao` nula de ausente; limpar a descrição só funciona mandando texto em branco (F11). Dono: backend, junto com a dívida acima.
- **DÍVIDA** — PUT numa parcela muda título e categoria só dela, e a tela da compra mostra o título da primeira; a categoria divergente entra no gráfico por competência (QA da F11, S1; revisão do PR #17). Decidir: recusar título e categoria no PUT de parcela com `edite a compra inteira`, ou a compra mostrar a divergência. Dono: tech-lead e backend, na fatia de gráfico por categoria.
- **DÍVIDA** — Os `isConstraint` antigos de `EntryService` e `InstallmentService` repetem o que `web/Constraints.isViolated` faz (F12). Trocar todos pelo helper num refator próprio. Dono: backend, na próxima fatia que mexer em `entries`.
- **DÍVIDA** — As descrições de 400 em `/lancamentos`, `/recorrencias` e `/transferencias` no OpenAPI não listam as mensagens novas da F12; elas estão só em `api.md` (F12). Dono: backend, junto do refator acima.
- **DÍVIDA** — Recorrência cuja categoria saiu da lista de um benefício é pulada pelo job em toda rodada, com warn, até a lista voltar (F12). Desde a F13.1 o usuário troca a categoria pelo editar; falta avisar na tela que a recorrência parou. Dono: frontend.
- **DÍVIDA** — `isCategoryPermitted` repetida em `EntryQueries` e `RecurrenceQueries` (revisão do PR #19). Juntar num lugar só no refator dos helpers de constraint. Dono: backend.
- **DÍVIDA** — `PUT /contas/{id}/categorias-permitidas` aceita conta arquivada (QA da F12, S2). Inofensivo, porque lançar em conta arquivada já é recusado; decidir se recusa para o contrato ficar coerente com o resto das escritas. Dono: backend.
- **DÍVIDA** — Ativo e cotação de cripto não se editam nem se apagam (F13): ativo cadastrado errado fica na lista, e cotação digitada errada só se corrige com outra mais nova. Rota de correção quando houver pedido, ou na F14 junto com a fonte automática. Dono: backend e frontend.
- **DÍVIDA** — `GET /movimentos-cripto` sem paginação e sem teto de movimentos por usuário (F13), como `GET /lancamentos`. Paginar junto com a importação. Dono: backend.
- **DÍVIDA** — As rotas de cripto não têm teste de banco congelado (503) próprio; o caminho é o mesmo `WriteTransaction` e `ApiExceptionHandler` já testados nas outras rotas (F13). Dono: backend, na F14.
- **DÍVIDA** — Arquivar uma conta cripto tira a posição dela do patrimônio sem aviso na tela (revisão do PR #20). É o contrato ("só contas ativas"), mas a queda no total confunde. Avisar na aba Cripto quando houver conta cripto arquivada com posição. Dono: frontend.
- **DÍVIDA** — `isCryptoAccount` repetida em `EntryQueries` e `RecurrenceQueries` (revisão do PR #20), como a `isCategoryPermitted`. Juntar no mesmo refator dos helpers. Dono: backend.
- **DÍVIDA** — Um movimento de cripto aceito pela escrita pode deixar `/patrimonio` e `/posicoes-cripto` em 400 `patrimônio fora do limite` até ser apagado: quantidade perto de 10²⁰ vezes qualquer cotação passa do inteiro seguro (QA da F13, D1). Só com valor absurdo. Conferir na escrita o valor com a última cotação, ou devolver a posição sem valor em vez do 400. Dono: backend, na F14.
- **DÍVIDA** — O saldo projetado sem `contaId` ainda conta a conta cripto no escopo, e `?contaId=<cripto>` responde zeros (QA da F13, S1). Sem efeito no número: a conta cripto não tem saldo nem lançamento. Tirar do `ALL_ACTIVE` junto com a próxima mudança da projeção. Dono: backend.
- **DÍVIDA** — Apagar conta com muito lançamento segura as travas da conta e das contrapartes sem teto de tempo de execução; só a espera por trava tem prazo (auditoria do PR #21, B1). `SET LOCAL statement_timeout` nessa transação ou limite de volume, medido quando houver hospedagem. Dono: backend e dba.
- **DÍVIDA** — `DELETE /contas/{id}` e `DELETE /recorrencias/{id}` não têm corpo, então a defesa de CSRF do ADR-020 se apoia só no `SameSite=Lax` (auditoria do PR #21, B3). Se PWA e API ficarem em subdomínios do mesmo site, exigir `Origin` conferido ou cabeçalho próprio nas rotas que mudam estado. Dono: devops e backend, na escolha da hospedagem.
- **DÍVIDA** — Editar uma recorrência inativa de conta arquivada é recusado com `conta arquivada`, como o POST, mesmo para corrigir só o título (revisão do PR #21). Decidir se a edição de template inativo deixa passar conta arquivada. Dono: backend.
- **DÍVIDA** — A conversão da perna de transferência ao apagar conta busca a categoria "Outros"/"Outras receitas" pelo nome; sem a semente, o DELETE dá 500 (revisão do PR #21). Hoje toda conta recebe a semente e não há CRUD de categoria. Resolver na fatia do CRUD de categoria, marcando as sementes como fixas. Dono: backend.
- **DÍVIDA** — Editar recorrência, ou apagar conta, ao mesmo tempo que se edita uma ocorrência dela dá 503 por deadlock em 5 de 5 rodadas (QA da F13.1, D1). Sem 500 e sem dado gravado pela metade; repetir resolve. Suspeita: o DELETE das previstas sem ordem contra o UPDATE que marca `desgarrado`, ou a FK de `lancamento.template_id` pedindo KEY SHARE no template travado FOR UPDATE. Reproduzir com `pg_locks`, corrigir a ordem e travar com teste de concorrência. Dono: backend e dba.
- **DÍVIDA** — Trocar a divisão de salário de uma recorrência depois de uma parcela realizada no mês deixa a soma do mês diferente do total (R$ 1.000 em 2 partes com a 1ª realizada, trocado para 3 partes: o mês soma R$ 1.166,70) (QA da F13.1, D2). Coerente com "realizada fica", mas nada avisa. Avisar no formulário de edição ou regerar o mês corrente inteiro quando a divisão muda. Dono: tech-lead com o dono do produto.
- **DÍVIDA** — `conta.aceita_lancamento` e a FK `lancamento_conta_aceita_lancamento` da V13 ficaram redundantes com `recebe_lancamento` da V14 (ADR-037). Remover é DROP de coluna: pare e avise o dono antes. Dono: dba.
- **DÍVIDA** — Desfazer o resgate de um título só apagando e cadastrando de novo; não há rota para tirar o resgate (F13.2). Dono: backend e frontend.
- **DÍVIDA** — O IPCA do título usa o IPCA do mês (ou o último publicado) com pro rata pelos dias corridos, sem a projeção nem o aniversário que o banco usa; a marcação corrige (ADR-037). Rever se o desvio incomodar. Dono: backend.
- **DÍVIDA** — O e2e e o CI sobem a API com o job de índices ligado, que consulta o Banco Central de verdade ao subir. O fluxo não depende do resultado (título aplicado no dia vale o aplicado), mas é rede externa num teste. Desligar no `scripts/e2e.sh` ou usar uma fonte local. Dono: devops.
- **DÍVIDA** — Título vencido continua rendendo depois do vencimento (revisão do PR #22). Parar o cálculo no vencimento, ou avisar na tela que venceu. Dono: backend e frontend.
- **DÍVIDA** — Resgate numa data anterior a uma marcação deixa a marcação "depois do resgate" e ela aparece como último valor do banco (revisão do PR #22). Recusar ou esconder. Dono: backend.
- **DÍVIDA** — Custo do cálculo: raiz 252 por Newton refeita por título a cada leitura, e um INSERT por índice no primeiro download (~1.260 linhas por 5 anos). Memorizar a raiz por taxa e gravar em lote (revisão do PR #22). Dono: backend, quando medir lentidão.
- **DÍVIDA** — Sem teto de títulos por usuário nem rate limit nas leituras de renda fixa e patrimônio (auditoria do PR #22, M1). Entra com os outros tetos e o rate limit da F20.5. Dono: backend.
- **DÍVIDA** — Resposta do Banco Central sem teto de bytes nem prazo total (auditoria do PR #22, B1); o `docs/ameacas.md` pede que resposta gigante não derrube o worker. Ler com limite. Dono: backend.
- **DÍVIDA** — "Ver posições" da conta cripto e o link antigo `#/cripto` abrem Investimentos na aba Renda fixa (revisão do PR #22). Aceitar a aba na rota, como `#/investimentos/cripto`. Dono: frontend.
- **DÍVIDA** — O web usa `CriptoNaoEncontradoError` e `recusarRespostaDeCripto` também para título de renda fixa (revisão do PR #22). Renomear para um erro de investimento. Dono: frontend.
- **DÍVIDA** — Um título no teto de valor faz a lista de renda fixa e o patrimônio responderem 400 `fora do limite` sem dizer qual título (QA da F13.2, D2). Devolver o título sem valor, como a posição sem cotação. Dono: backend.
- **DÍVIDA** — Cadastrar título com aplicação antiga e o Banco Central mudo segura a requisição até 10 s (2 séries x 5 s) (QA da F13.2, D3). Levar a busca para fora da requisição. Dono: backend.
- **DÍVIDA** — Sem IPCA guardado, o título IPCA + taxa rende só a taxa; com CDI incompleto (Banco Central fora na primeira aplicação), o valor sai menor; a tela só avisa quando não há CDI nenhum (QA da F13.2, D4 e D5). Avisar por título quando o índice não cobre o período. Dono: backend e frontend.
- **DÍVIDA** — Editar a aplicação para depois de uma marcação tira a marcação do cálculo, mas ela continua como último valor do banco na tela (QA da F13.2, D6). Recusar ou esconder. Dono: backend.
- **DÍVIDA** — `IndexQueries.earliestInvestment` também olha movimentos de cripto e o nome mente; e faltam testes de venda com PTAX, de posição misturando movimento em reais e em dólar e de meio centavo na conversão (revisão do PR #23). Dono: backend.
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
