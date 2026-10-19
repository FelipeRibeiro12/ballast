# Modelo de dados

Contrato entre `dba` e `backend`. Mudança aqui exige migration e atualização
deste arquivo no mesmo PR.

Nomes de tabela e coluna em inglês no código; aqui em português por clareza de
domínio. Ao implementar, traduza mantendo o conceito.

Este documento descreve o modelo-alvo. As migrations constroem em direção a
ele, e nem toda coluna aqui existe hoje. O estado atual é o schema: veja
a tabela `flyway_schema_history` e `api/src/main/resources/db/migration/`.

**Usuários de banco (ADR-021):** o Flyway conecta como dono do schema
(`DB_MIGRATION_USER`); a aplicação conecta como `DB_USER`, só com
`SELECT/INSERT/UPDATE/DELETE`. A `V3` concede o DML nas tabelas atuais e, por
`ALTER DEFAULT PRIVILEGES`, nas futuras. Tabela criada por migration já nasce
acessível à aplicação; `TRUNCATE` e DDL ficam só com o dono. A `V4` tira da
aplicação o acesso a `flyway_schema_history`, que a V3 cobriu sem querer.

**Dinheiro (ADR-005):** fiat é `numeric(18,2)` no banco e `BigDecimal` no Java,
e trafega no JSON como inteiro em centavos. Cripto é `numeric(38,18)` e trafega
como string decimal. A precisão 18 garante que todo valor fiat cabe em `long`
de centavos.

---

## A decisão central: competência × caixa

Todo lançamento tem **duas datas**, e entender a diferença é o que faz cartão
de crédito funcionar:

| Campo | O que é | Usado em |
|---|---|---|
| `data_competencia` | quando o fato aconteceu (a compra) | gráfico por categoria, orçamento do mês |
| `data_caixa` | quando o dinheiro sai ou entra de verdade | saldo, saldo projetado, fluxo de caixa |

Compra no cartão dia 03/10, fatura vence 10/11: competência 03/10, caixa 10/11.

Em débito, dinheiro e Pix as duas datas são iguais. Em cartão, não. Em salário
com divisão, cada parcela tem a sua.

Modelar isso na primeira migration custa nada. Adicionar depois exige
reinterpretar todo lançamento já gravado.

---

## Usuário e conta

```
usuario
  id              uuid pk
  email           text unique not null
  senha_hash      text not null          -- argon2id
  nome            text
  mfa_segredo     text                   -- cifrado, nullable (F20)
  criado_em       timestamptz not null
```

```
sessao                                   -- ADR-015
  id              uuid pk
  usuario_id      uuid fk not null
  token_hash      bytea unique not null  -- SHA-256 do token; o token só existe no cookie
  criada_em       timestamptz not null
  expira_em       timestamptz not null
  revogada_em     timestamptz            -- logout e troca de senha
```

```
tentativa_login                          -- atraso progressivo por conta
  email           text pk                -- normalizado (minúsculas); vale também para e-mail inexistente
  falhas          int not null           -- consecutivas; zera no login com sucesso
  ultima_falha_em timestamptz not null
```

`tentativa_login` não tem `usuario_id` de propósito: a chave é o e-mail
digitado, para o app não revelar se a conta existe (`docs/ameacas.md`).

Migration `V2`: `mfa_segredo` ainda não existe (entra na F20). No banco,
`email` tem CHECK de minúsculas e no máximo 254 caracteres (nas duas tabelas
que o guardam), `nome` no máximo 100, `token_hash` exatamente 32 bytes,
`falhas >= 0`, e `criado_em`/`criada_em` têm `DEFAULT now()`. `sessao.usuario_id`
é `ON DELETE CASCADE` e tem índice. A aplicação normaliza o e-mail antes de gravar.

Todo `id` é UUID v7, gerado na aplicação: o Postgres 17 não tem `uuidv7()`
nativo (chega no 18). Sem default no banco.

**Toda tabela abaixo tem `usuario_id` e toda query é escopada por ele.** Sem
exceção — é a fronteira de autorização do app inteiro.

```
conta
  id                uuid pk
  usuario_id        uuid fk not null
  nome              text not null
  tipo              text not null       -- ver abaixo
  moeda             char(3) not null default 'BRL'
  saldo_inicial     numeric(18,2) not null default 0
  arquivada         bool not null default false
  criada_em         timestamptz not null
  unique index (usuario_id, lower(nome))
```

`tipo` no modelo-alvo: `corrente` · `poupanca` · `dinheiro` · `beneficio` ·
`cartao_credito` · `cripto`.

Migration `V5` (ADR-023): o `CHECK` de `tipo` aceita só `corrente`, `poupanca` e
`dinheiro`, e o de `moeda` só `BRL`. Cada fatia que trouxer outro tipo alarga o
`CHECK` por migration. O nome tem de 1 a 100 caracteres e não tem espaço nas
pontas. `saldo_inicial` fica entre -90071992547409.91 e 90071992547409.91, o
`Number.MAX_SAFE_INTEGER` em centavos. O nome é único por usuário sem diferenciar
caixa (`conta_usuario_nome_unico`), inclusive contra conta arquivada. Esse índice
também atende a busca por `usuario_id`. `usuario_id` é `ON DELETE CASCADE`,
`criada_em` tem `DEFAULT now()`, e o `id` vem da aplicação.

Migration `V6` (ADR-024): `conta` ganha `unique (id, usuario_id)`
(`conta_id_usuario_unico`), o alvo da FK composta de `lancamento`.

Saldo **não** é coluna. É `saldo_inicial` + soma dos lançamentos realizados.
Guardar saldo materializado é a origem clássica de divergência; se a
performance exigir, isso vira view materializada depois, com ADR.

### Benefício com restrição

O VR só paga comida. Isso é propriedade da conta, não tag do lançamento.

```
conta_categoria_permitida
  conta_id      uuid fk
  categoria_id  uuid fk
  primary key (conta_id, categoria_id)
```

Conta de benefício sem nenhuma linha aqui aceita qualquer categoria. Com
linhas, só as listadas — validado na aplicação **e** por trigger, porque é
invariante de dinheiro.

### Cartão de crédito

```
cartao
  conta_id            uuid pk fk       -- 1:1 com conta tipo cartao_credito
  limite              numeric(18,2) not null
  dia_fechamento      smallint not null check (between 1 and 31)
  dia_vencimento      smallint not null check (between 1 and 31)
  conta_pagamento_id  uuid fk          -- de onde sai o dinheiro da fatura
```

A regra que gera `data_caixa` de uma compra no cartão:

> Se `data_competencia` ≤ dia de fechamento do mês corrente, a compra entra na
> fatura que vence neste mês ou no próximo (conforme vencimento > fechamento).
> Se for depois do fechamento, entra na fatura seguinte.

Casos de borda que **precisam de teste**: fechamento dia 31 em fevereiro,
vencimento menor que fechamento (vira o mês), compra no próprio dia do
fechamento.

### Cripto

```
ativo_cripto
  id          uuid pk
  rede        text not null         -- ethereum | base | arbitrum | polygon | solana
  contrato    text null             -- null = ativo nativo (ETH, SOL)
  simbolo     text not null         -- BTC, ETH, USDC
  nome        text
  decimais    smallint not null     -- BTC 8, ETH 18, USDC 6
  confiavel   bool not null default false
  unique (rede, contrato)

posicao_cripto
  id          uuid pk
  conta_id    uuid fk not null
  ativo_id    uuid fk not null
  quantidade  numeric(38,18) not null default 0
  origem      text not null default 'manual'   -- manual | sincronizada
  unique (conta_id, ativo_id)

cotacao
  ativo_id    uuid fk
  momento     timestamptz
  preco       numeric(20,8) not null    -- em BRL
  fonte       text not null
  primary key (ativo_id, momento)
```

`quantidade` é a verdade; valor em reais é sempre derivado (ADR-006).

**`rede` + `contrato` é a identidade do ativo, não o símbolo.** USDC na
Ethereum e USDC na Base são registros diferentes, com contratos diferentes. Dois
tokens podem ter o mesmo símbolo e nenhuma relação — é assim que token falso se
disfarça.

**`confiavel`** começa `false`. Ativo desconhecido entra oculto e o usuário
promove o que interessa. Sem isso, a primeira sincronização de carteira traz
airdrop de spam com cotação inventada e o patrimônio exibido fica errado
(ADR-009).

**`origem`** separa o que você digitou do que veio da rede. Posição
`sincronizada` não é editável à mão — a próxima sincronização sobrescreveria.

### Carteira observada

Endereço público que alimenta uma conta do tipo `cripto`. Cadastrado digitando
ou conectando a carteira — o resultado é o mesmo (ADR-009).

```
carteira_observada
  id               uuid pk
  usuario_id       uuid fk not null
  conta_id         uuid fk not null
  rede             text not null
  endereco         text not null        -- 0x... (EVM) ou base58 (Solana)
  rotulo           text
  verificada_em    timestamptz null     -- assinou provando posse (opcional)
  sincronizada_em  timestamptz null     -- última leitura da rede
  erro_ultima_sync text null
  ativa            bool not null default true
  unique (usuario_id, rede, endereco)
```

`verificada_em` é opcional por decisão: endereço é público, observar não exige
prova de posse. A assinatura serve para o usuário ter certeza de que cadastrou
o endereço certo.

`erro_ultima_sync` existe porque o indexador vai falhar, e a interface precisa
mostrar "última leitura em tal data" em vez de um saldo silenciosamente velho.

```
movimentacao_cripto
  id             uuid pk
  usuario_id     uuid fk not null
  posicao_id     uuid fk not null
  tipo           text not null       -- compra | venda | entrada | saida
  quantidade     numeric(38,18) not null
  preco_unitario numeric(20,8) null  -- custo de aquisição, em BRL
  momento        timestamptz not null
  hash_tx        text null           -- hash da transação on-chain
  origem         text not null       -- manual | sincronizada
  unique (hash_tx, posicao_id)
```

`preco_unitario` é **nullable de propósito**: a rede não informa preço de
aquisição. Transferência que entra vinda de corretora chega sem custo. Sem esse
dado, "aporte × valorização" não fecha — então a interface pede o custo quando
estiver faltando, em vez de inventar.

---

## Categoria

```
categoria
  id          uuid pk
  usuario_id  uuid fk not null
  nome        text not null
  tipo        text not null          -- receita | despesa
  cor         text
  icone       text
  pai_id      uuid fk null           -- "Alimentação > Restaurante"
  unique (usuario_id, pai_id, lower(nome))
```

Hierarquia de um nível só. Dois níveis já cobrem o uso real e mantêm a consulta
simples; mais que isso vira árvore e a agregação fica custosa.

Migration `V6` (ADR-024): o nome tem de 1 a 100 caracteres, sem espaço nas
pontas. `cor` e `icone` são opcionais, com no máximo 9 e 50 caracteres (cabe
`#RRGGBBAA` e um nome de ícone). `usuario_id` é `ON DELETE CASCADE`, e o `id`
vem da aplicação.

- **Nome único por pai.** `categoria_usuario_pai_nome_unico` é um índice único
  em `(usuario_id, pai_id, lower(nome)) NULLS NOT DISTINCT`. O `NULLS NOT DISTINCT`
  (Postgres 15+) faz duas raízes de mesmo nome colidirem; num `unique` comum o
  `pai_id` nulo nunca colide. Nomes iguais sob pais diferentes são permitidos.
- **Pai do mesmo usuário e de um nível só, sem trigger.** `pai_id` é FK
  composta `(pai_id, usuario_id, pai_nivel)` para `categoria (id, usuario_id,
  nivel)`. `nivel` (0 raiz, 1 filha) e `pai_nivel` (0 quando há pai, nulo sem pai)
  são colunas geradas: **a aplicação não escreve nelas** e o INSERT não as cita.
  Como a FK exige pai de `nivel` 0, uma filha não vira pai, e uma raiz com filhas
  não vira filha. A categoria filha pode ter tipo diferente do pai; se isso
  importar, é CHECK novo.
- **Alvo da FK de `lancamento`:** `unique (id, usuario_id, tipo)`
  (`categoria_id_usuario_tipo_unico`). Não existe `unique (id, usuario_id)` sozinho
  porque nada aponta para ele; quem precisar cria.
- As FKs de `categoria` e `lancamento` são `ON DELETE NO ACTION`, não `RESTRICT`.
  Recusam apagar o pai com filho do mesmo jeito, mas só checam no fim do comando.
  Com `RESTRICT`, apagar o usuário falharia no meio do cascade, quando a conta
  some antes dos lançamentos.
- Categoria com lançamento não se apaga (a FK recusa). O CRUD de categoria
  (fatia futura) decide entre arquivar e bloquear.

Sementes (usuários existentes na `V6`, usuários novos pela aplicação, **a mesma
lista nos dois lugares**; esta é a fonte):

- Receita: Salário, Aluguel recebido, Benefício, Rendimento, Outras receitas
- Despesa: Alimentação, Refeição, Mercado, Combustível, Transporte, Moradia,
  Saúde, Lazer, Assinatura, Educação, Outros

São 16. "Outros" é despesa e a receita genérica chama "Outras receitas" (ADR-024).
A `V6` gera o id das sementes com `gen_random_uuid()` (v4), só no backfill.

---

## Recorrência — o coração do app

Três tabelas, e a separação entre elas é o que permite editar uma ocorrência
sem quebrar a série.

```
regra_recorrencia
  id              uuid pk
  usuario_id      uuid fk not null
  frequencia      text not null        -- diaria | semanal | mensal | anual
  intervalo       smallint not null default 1   -- a cada N
  dias_do_mes     smallint[]           -- [15, 30] ou [5]
  dia_da_semana   smallint             -- 0..6, para frequencia semanal
  inicio          date not null
  fim             date null
  ocorrencias_max int null
  check (fim is null or ocorrencias_max is null)
```

`dias_do_mes` com valor 29, 30 ou 31 precisa de regra para mês curto. Decisão:
**usa o último dia do mês**. Documentado aqui porque é escolha, não óbvio.

```
template_lancamento
  id            uuid pk
  usuario_id    uuid fk not null
  tipo          text not null        -- receita | despesa
  titulo        text not null
  descricao     text
  categoria_id  uuid fk not null
  conta_id      uuid fk not null
  valor_total   numeric(18,2) not null  -- o cheio, antes da divisão
  regra_id      uuid fk not null
  ativo         bool not null default true
```

### Divisão — o caso do salário em dois dias

```
template_divisao
  id            uuid pk
  template_id   uuid fk not null
  dia_do_mes    smallint not null
  percentual    numeric(5,2) null    -- 40.00
  valor_fixo    numeric(18,2) null
  ordem         smallint not null
  check ((percentual is null) != (valor_fixo is null))
  unique (template_id, dia_do_mes)
```

Suporta os dois modos que você descreveu, e mais de dois dias:

- **Por percentual:** valor cheio R$ 5.000, dia 15 = 40%, dia 30 = 60%
- **Por valor:** dia 15 = R$ 2.000, dia 30 = R$ 3.000

Sem nenhuma linha de divisão, o template gera uma ocorrência por período.

**Regra do centavo:** em divisão por percentual, calcula-se cada parcela com
truncamento (`setScale(2, RoundingMode.DOWN)`) e o resto vai para a **última** parcela pela `ordem`. A soma das
parcelas é sempre igual ao `valor_total` — isso é invariante e precisa de teste
(ex.: R$ 1.000,00 em três partes de 33,33% não pode somar R$ 999,99).

Validação: soma dos percentuais = 100, ou soma dos valores fixos =
`valor_total`.

### Ocorrências

```
lancamento
  id                uuid pk
  usuario_id        uuid fk not null
  tipo              text not null      -- receita | despesa | transferencia
  titulo            text not null
  descricao         text
  categoria_id      uuid fk
  conta_id          uuid fk not null
  valor             numeric(18,2) not null  -- sempre positivo
  data_competencia  date not null
  data_caixa        date not null
  status            text not null      -- previsto | realizado | cancelado
  template_id       uuid fk null       -- de onde veio
  desgarrado        bool not null default false
  parcela_de_id     uuid fk null       -- self-fk: compra parcelada
  parcela_num       smallint null
  parcela_total     smallint null
  hash_externo      text null          -- FITID do OFX, ou hash dos campos
  importacao_linha_id uuid fk null
  criado_em         timestamptz not null
  unique (usuario_id, hash_externo)
```

Migration `V6` (ADR-024) cria só o recorte da F3: `id`, `usuario_id`, `tipo`,
`titulo`, `descricao`, `categoria_id`, `conta_id`, `valor`, `data_competencia`,
`data_caixa`, `status`, `criado_em`. As demais colunas e tabelas deste bloco
chegam com as suas fatias.

- `tipo` aceita só `receita` e `despesa` (a F4 alarga para `transferencia` e
  permite `categoria_id` nulo só nela). `status` aceita só `realizado` e tem
  `DEFAULT 'realizado'` (a recorrência alarga para `previsto` e `cancelado`).
- `titulo` de 1 a 100 caracteres sem espaço nas pontas; `descricao` nula ou até
  500 caracteres (conta caracteres, não bytes).
- `valor` entre 0.01 e 90071992547409.91 (`lancamento_valor_faixa`). As duas
  datas entre 1900-01-01 e 2199-12-31. `criado_em` tem `DEFAULT now()`; o `id`
  vem da aplicação. `usuario_id` é `ON DELETE CASCADE`.
- **O dono é garantido pelo banco.** `lancamento_conta_mesmo_usuario`: FK
  `(conta_id, usuario_id)` para `conta (id, usuario_id)`.
  `lancamento_categoria_mesmo_usuario_e_tipo`: FK `(categoria_id, usuario_id,
  tipo)` para `categoria (id, usuario_id, tipo)`, o que também impede despesa
  em categoria de receita e o contrário. Ambas `ON DELETE NO ACTION` (motivo na
  seção Categoria): conta e categoria com lançamento não se apagam.
- Índice da F3: só `lancamento_usuario_data_caixa (usuario_id, data_caixa)`.
  Atende a lista por intervalo (com `conta_id` como filtro), o saldo por conta
  (lê os lançamentos do usuário) e a busca por `usuario_id` e `id` usa a chave
  primária. Medido com 400 mil linhas: lista de um mês (5 mil linhas) 4 ms,
  saldo de usuário com 2 mil lançamentos 0,7 ms, de usuário com 300 mil
  (75% da tabela) 26 ms com seq scan, que nenhum índice melhora. Índice por
  `conta_id` ou `categoria_id` ficou de fora: ganhou 0,3 ms no saldo e a
  checagem das FKs na exclusão do usuário caiu de 5 ms para 2 ms. Quem trouxer
  CRUD de categoria ou relatório cria o dele com o `EXPLAIN`.
- Query do saldo: `sum` por `conta_id` filtrando `usuario_id` e
  `status = 'realizado'`, com receita somando e despesa subtraindo.

Pontos que importam:

- **`valor` sempre positivo.** O sinal vem do `tipo`. Valor negativo mais tipo
  gera dupla negação e bug de relatório.
- **`status`**: `previsto` é o futuro gerado pela recorrência; `realizado` é o
  que aconteceu. Saldo soma só `realizado`; saldo projetado soma os dois.
- **`desgarrado`**: você editou essa ocorrência. A materialização nunca
  sobrescreve linha desgarrada. É o que permite "em março veio com bônus".
- **`parcela_de_id`**: compra em 12x gera 12 linhas apontando para a primeira,
  com `parcela_num` 1..12 e a mesma `data_competencia`, mas `data_caixa`
  avançando um mês por parcela.

A materialização roda em lote e gera previstos **12 meses à frente**, sendo
idempotente: rodar duas vezes não duplica. A chave de idempotência é
`(template_id, data_competencia)` para linhas não desgarradas.

### Transferência

Transferência não é receita nem despesa. Esquecer isso duplica o patrimônio.

```
transferencia
  id              uuid pk
  usuario_id      uuid fk not null
  saida_id        uuid fk not null unique    -- lancamento na conta origem
  entrada_id      uuid fk not null unique    -- lancamento na conta destino
```

Duas linhas de `lancamento` com `tipo = transferencia`, ligadas. Todo
relatório de receita ou despesa **exclui** esse tipo.

Pagamento de fatura de cartão é uma transferência: sai da conta corrente, entra
na conta do cartão.

---

## Metas

```
meta
  id                uuid pk
  usuario_id        uuid fk not null
  titulo            text not null
  valor_alvo        numeric(18,2) not null
  data_alvo         date null
  conta_id          uuid fk null       -- onde o dinheiro está guardado
  regra_id          uuid fk null       -- aporte recorrente
  aporte_previsto   numeric(18,2) null
  concluida_em      timestamptz null

aporte_meta
  id            uuid pk
  meta_id       uuid fk not null
  lancamento_id uuid fk null           -- se veio de um lançamento real
  valor         numeric(18,2) not null
  data          date not null
```

Progresso é `soma(aportes) / valor_alvo`. Com `data_alvo` e `aporte_previsto`,
dá para responder "no ritmo atual, chego em qual data" — que é mais útil que a
porcentagem sozinha.

## Importação de extrato e fatura

Duas etapas: a importação grava as linhas cruas e **propõe** uma decisão; o
usuário confirma. Nada entra no extrato sem confirmação (ADR-008).

```
importacao
  id             uuid pk
  usuario_id     uuid fk not null
  conta_id       uuid fk not null       -- destino dos lançamentos
  formato        text not null          -- ofx | csv | pdf_fatura
  nome_arquivo   text not null
  hash_arquivo   text not null          -- sha256 do conteúdo
  periodo_inicio date null
  periodo_fim    date null
  status         text not null          -- processando | revisao | concluida | falhou
  erro           text null
  criada_em      timestamptz not null
  unique (usuario_id, hash_arquivo)

importacao_linha
  id              uuid pk
  importacao_id   uuid fk not null
  linha_bruta     jsonb not null         -- o que veio, cru, para auditoria
  hash_externo    text null              -- FITID, ou hash de (data, valor, descrição)
  data            date null              -- campos já interpretados
  valor           numeric(18,2) null
  descricao       text null
  decisao         text not null default 'pendente'
                  -- pendente | importar | ignorar | conciliar
  lancamento_id   uuid fk null           -- criado a partir dela
  conciliado_com  uuid fk null           -- lançamento manual que já existia
```

`unique (usuario_id, hash_arquivo)` impede reimportar o mesmo arquivo.
`unique (usuario_id, hash_externo)` em `lancamento` impede a mesma transação
entrar duas vezes por arquivos diferentes que se sobrepõem — extrato de
setembro e de outubro costumam repetir os dias da virada.

### Conciliação

O problema real da importação não é ler o arquivo: é que você já lançou o
almoço de ontem na mão e o extrato traz o mesmo almoço.

A proposta de decisão por linha, em ordem:

1. `hash_externo` bate com lançamento existente → **ignorar** (já está lá)
2. Mesma conta, valor exatamente igual, data a até 3 dias de distância →
   **conciliar**, propondo o lançamento candidato
3. Nada bateu → **importar**

O passo 2 é heurística e vai errar. É exatamente por isso que existe a etapa de
revisão: errar sozinho no saldo é inaceitável.

Conciliar não cria lançamento novo: preenche `hash_externo` no lançamento que
já existia e marca a linha como resolvida.

O arquivo original **não é guardado** depois de processado. O que fica é
`linha_bruta`, suficiente para auditar.

## Orçamento

```
orcamento
  id            uuid pk
  usuario_id    uuid fk not null
  categoria_id  uuid fk not null
  mes           date not null         -- sempre dia 1
  teto          numeric(18,2) not null
  unique (usuario_id, categoria_id, mes)
```

Consumo usa `data_competencia`, não `data_caixa`: o orçamento de outubro conta
a compra feita em outubro, mesmo que a fatura caia em novembro.

---

## Invariantes que o banco garante

Não só a aplicação — porque duas requisições simultâneas furam validação feita
apenas em código:

1. Soma de `template_divisao` = 100% ou = `valor_total`
2. `percentual` e `valor_fixo` mutuamente exclusivos (`CHECK` já acima)
3. `valor > 0` em todo lançamento
4. Lançamento em conta de benefício só com categoria permitida (trigger)
5. `parcela_num` ≤ `parcela_total`
6. `regra_recorrencia`: `fim` e `ocorrencias_max` não coexistem
7. Transferência: `saida_id` e `entrada_id` em contas diferentes
8. `hash_externo` único por usuário (deduplicação de importação)
9. `hash_arquivo` único por usuário (mesmo arquivo não reimporta)
10. `hash_tx` único por posição (mesma transação não duplica)
11. `carteira_observada`: endereço único por usuário e rede
12. `posicao_cripto` com `origem = 'sincronizada'` não aceita escrita manual

## Índices iniciais

Só o que tem query real, conforme `stacks/sql.md`:

```sql
create index on lancamento (usuario_id, data_caixa);
create index on lancamento (usuario_id, data_competencia, categoria_id);
create index on lancamento (template_id) where template_id is not null;
create index on lancamento (parcela_de_id) where parcela_de_id is not null;
create index on cotacao (ativo_id, momento desc);
create index on lancamento (usuario_id, hash_externo) where hash_externo is not null;
create index on importacao_linha (importacao_id, decisao);
```

Na `V6` só o primeiro existe; os outros chegam com as fatias que têm a query.
O primeiro serve saldo e fluxo de caixa; o segundo, gráfico por categoria e
orçamento. Antes de adicionar qualquer outro, rode `EXPLAIN ANALYZE` e mostre o
antes e o depois.
