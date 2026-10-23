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

Migration `V10` (ADR-031): o `CHECK` de `tipo` aceita também `cartao_credito`
(detalhes em Cartão de crédito).

Saldo **não** é coluna. É `saldo_inicial` + soma dos lançamentos realizados.
Guardar saldo materializado é a origem clássica de divergência; se a
performance exigir, isso vira view materializada depois, com ADR.

### Benefício com restrição

O VR só paga comida. Isso é propriedade da conta, não tag do lançamento.

```
conta_categoria_permitida
  conta_id        uuid fk not null    -- conta de tipo beneficio do mesmo usuário
  categoria_id    uuid fk not null    -- categoria de tipo despesa do mesmo usuário
  usuario_id      uuid fk not null    -- ON DELETE CASCADE
  tipo_conta      text gerada         -- sempre 'beneficio'
  tipo_categoria  text gerada         -- sempre 'despesa'
  primary key (conta_id, categoria_id)
```

Conta de benefício sem nenhuma linha aqui aceita qualquer categoria. Com
linhas, só as listadas, validado na aplicação **e** por gatilho, porque é
invariante de dinheiro. A regra vale **só para despesa** (ADR-034): a recarga
é receita e usa a categoria que o usuário quiser, e transferência não participa.

Migration `V12` (ADR-034):

- `conta_tipo_valido` aceita também `beneficio`. `cripto` entra na `V13`.
- FKs compostas pelo padrão de coluna gerada da `V10`:
  `conta_categoria_permitida_conta_beneficio_do_usuario` (`conta_id`,
  `usuario_id`, `tipo_conta`) para `conta (id, usuario_id, tipo)`, e
  `conta_categoria_permitida_categoria_despesa_do_usuario` (`categoria_id`,
  `usuario_id`, `tipo_categoria`) para `categoria (id, usuario_id, tipo)`. A
  aplicação não escreve nas colunas geradas.
- `ON DELETE`: apagar a conta apaga a lista (`CASCADE`, a lista é propriedade
  da conta). Apagar a categoria listada é recusado (`NO ACTION`): `CASCADE`
  tiraria a categoria da lista em silêncio e, sendo a última, a conta passaria
  a aceitar tudo. O `ON UPDATE` padrão recusa mudar o tipo de uma conta que
  tem lista.
- Gatilho `lancamento_categoria_permitida_no_beneficio`, `BEFORE INSERT OR
  UPDATE OF tipo, conta_id, categoria_id` em `lancamento`, `WHEN (NEW.tipo =
  'despesa')`, função com `search_path = public, pg_temp` e `SECURITY INVOKER`.
  Se a conta é de benefício e tem ao menos uma linha na lista, a categoria
  tem de estar nela; senão `RAISE` com `ERRCODE` `23514` e `CONSTRAINT`
  `lancamento_categoria_permitida_no_beneficio`, que a aplicação traduz pelo
  nome. Editar título, valor, data ou status não revalida: lançamento já
  gravado não muda quando a lista muda.
- Concorrência: o gatilho segura a conta com `FOR SHARE` (só quando é
  benefício) antes de ler a lista. A aplicação troca a lista segurando a conta
  `FOR UPDATE` e grava lançamento também com `FOR UPDATE` na conta (ADR-024),
  então as duas escritas se serializam e a leitura do gatilho, com snapshot
  novo em `READ COMMITTED`, vê a lista já confirmada. Quem trocar a lista sem
  travar a conta reabre a janela.
- Sem índice novo: a PK atende a busca por `(conta_id, categoria_id)` e por
  `conta_id`. A FK da categoria não tem índice, já que categoria não é apagada.
- Desfazer: bloco comentado no fim da `V12`. Apaga listas, contas de benefício
  e os lançamentos delas.

### Cartão de crédito

```
cartao
  conta_id            uuid pk fk       -- 1:1 com conta tipo cartao_credito
  usuario_id          uuid fk not null
  limite              numeric(18,2) not null check (> 0 e <= 90071992547409.91)
  dia_fechamento      smallint not null check (between 1 and 31)
  dia_vencimento      smallint not null check (between 1 and 31)
  conta_pagamento_id  uuid fk not null -- de onde sai o dinheiro da fatura
  tipo_conta          text gerada      -- sempre 'cartao_credito'
  pagamento_aceito    bool gerada      -- sempre true
```

A regra que gera `data_caixa` de uma compra no cartão (ADR-031), com
fechamento `F`, vencimento `V` e dia maior que o último do mês caindo no último
dia:

1. O fechamento do mês da compra é o dia `F` daquele mês. Se a compra for **até
   o fechamento, inclusive**, entra na fatura que fecha naquele mês. Senão,
   entra na que fecha no mês seguinte.
2. Se `V > F`, a fatura vence no mesmo mês em que fecha. Se `V <= F`, vence no
   mês seguinte ao do fechamento.
3. `data_caixa` é o dia do vencimento dessa fatura. `data_competencia` é a data
   da compra.

Casos de borda que **precisam de teste**: fechamento dia 31 em fevereiro,
vencimento menor que fechamento (vira o mês), compra no próprio dia do
fechamento.

Migration `V10` (ADR-031):

- `conta_tipo_valido` passa a aceitar `corrente`, `poupanca`, `dinheiro` e
  `cartao_credito`. `beneficio` e `cripto` continuam fora.
- `conta` ganha `aceita_pagamento_cartao`, coluna gerada
  (`tipo IN ('corrente','poupanca')`), e dois uniques que são alvo das FKs de
  `cartao`: `conta_id_usuario_tipo_unico (id, usuario_id, tipo)` e
  `conta_id_usuario_aceita_pagamento_unico (id, usuario_id,
  aceita_pagamento_cartao)`. A aplicação não escreve na coluna gerada.
- `conta_id` é a chave primária: duas linhas para a mesma conta colidem em
  `cartao_pkey`. `usuario_id` é `ON DELETE CASCADE`.
- **Dono e tipo sem trigger.** `cartao_conta_mesmo_usuario_e_tipo`: FK
  `(conta_id, usuario_id, tipo_conta)` para `conta (id, usuario_id, tipo)`, com
  `tipo_conta` gerada constante, então só conta `cartao_credito` do mesmo
  usuário tem linha em `cartao`. `cartao_pagamento_mesmo_usuario_e_tipo`: FK
  `(conta_pagamento_id, usuario_id, pagamento_aceito)` para `conta (id,
  usuario_id, aceita_pagamento_cartao)`, então a conta de pagamento é do mesmo
  usuário e é corrente ou poupança. Ambas `ON DELETE NO ACTION`, o que também
  impede apagar ou mudar o tipo de uma conta que tem cartão ou paga um.
  `INSERT` em `cartao` não cita as duas colunas geradas.
- `cartao_pagamento_em_outra_conta`: `conta_pagamento_id <> conta_id`.
- **Fica com a aplicação:** conta de cartão sem linha em `cartao`. Garantir o 1:1
  do outro lado pediria gatilho adiável; `POST /contas` cria as duas na mesma
  transação. Conta de pagamento arquivada também é decisão do serviço.
- Sem índice novo. A busca do cartão pela conta é a chave primária. Para a lista
  de faturas, `(usuario_id, conta_id, data_caixa)` em `lancamento` foi medido e
  não entrou (seção Lançamento, "Índice da F10").

### Cripto

Recorte da F13 (ADR-035), migration `V13`. Cripto digitada à mão, sem carteira
observada e sem sincronização. `quantidade` é a verdade; valor em reais é
sempre derivado de uma cotação (ADR-006).

```
ativo_cripto
  id          uuid pk (sem DEFAULT, gerado na aplicação)
  usuario_id  uuid fk not null on delete cascade
  rede        text not null         -- bitcoin | ethereum | base | arbitrum | polygon | solana
  contrato    text null             -- null = ativo nativo (BTC, ETH, SOL)
  simbolo     text not null         -- 1 a 20, sem espaço nas pontas
  nome        text null             -- até 100
  decimais    smallint not null     -- 0 a 18 (BTC 8, ETH 18, USDC 6)
  confiavel   bool not null default false
  criado_em   timestamptz not null default now()
  unique nulls not distinct (usuario_id, rede, contrato)
  unique (id, usuario_id)           -- alvo das FKs compostas

movimento_cripto
  id          uuid pk (sem DEFAULT)
  usuario_id  uuid fk not null on delete cascade
  conta_id    uuid not null         -- FK composta: conta do mesmo usuário, tipo cripto
  ativo_id    uuid not null         -- FK composta: ativo do mesmo usuário
  tipo        text not null         -- compra | venda
  quantidade  numeric(38,18) not null   -- > 0
  data        date not null         -- 1900-01-01 a 2199-12-31
  custo       numeric(18,2) not null    -- > 0 e até 90071992547409.91, em reais
  criado_em   timestamptz not null default now()
  tipo_conta  text generated always as ('cripto') stored   -- a aplicação não escreve

cotacao
  usuario_id  uuid fk not null on delete cascade
  ativo_id    uuid not null         -- FK composta: ativo do mesmo usuário
  momento     timestamptz not null
  preco       numeric(20,8) not null    -- > 0, em reais por unidade
  fonte       text not null             -- 'manual' (a F14 alarga o CHECK)
  primary key (ativo_id, momento)
```

**Desvios do rascunho anterior desta seção, decididos no ADR-035:**

- `ativo_cripto` tem `usuario_id`. Não há catálogo global: `confiavel` é decisão
  de cada usuário, e a promoção de um não pode mudar o patrimônio de outro. A
  identidade é `(usuario_id, rede, contrato)`.
- **Não existe `posicao_cripto` na F13.** A posição de um ativo numa conta é a
  soma dos movimentos (compras menos vendas) até uma data, como o saldo é a
  soma dos lançamentos. `posicao_cripto` com quantidade guardada e `origem`
  fica para a sincronização da F24, com ADR próprio.
- `movimento_cripto` substitui `movimentacao_cripto` (abaixo) na F13: liga
  direto conta e ativo, só tem `compra` e `venda`, e o custo é o total em reais
  (`custo`), não `preco_unitario` nullable. Entrada e saída on-chain, `hash_tx`
  e `origem` chegam com a F24.
- `cotacao` tem `usuario_id`: a cotação manual é do usuário que a digitou. A
  F14 grava na mesma tabela com outra `fonte`.

**`rede` + `contrato` é a identidade do ativo, não o símbolo.** USDC na
Ethereum e USDC na Base são registros diferentes, com contratos diferentes. Dois
tokens podem ter o mesmo símbolo e nenhuma relação, e é assim que token falso se
disfarça.

**`confiavel`** nasce `false` no banco. O ativo digitado pelo usuário é gravado
com `true` pela aplicação (foi ele quem disse que existe); o `false` é para o
que vier da sincronização (ADR-009).

**Conta cripto.** `conta_tipo_valido` aceita `cripto`, e
`conta_cripto_sem_saldo` exige `saldo_inicial = 0` nela. Conta cripto não
recebe lançamento (ver abaixo).

**O que o banco garante na V13:**

- Contrato por rede (`ativo_cripto_contrato_formato`): nulo em qualquer rede;
  nas redes EVM (`ethereum`, `base`, `arbitrum`, `polygon`) `0x` mais 40
  hexadecimais em minúsculas; em `solana` base58 de 32 a 44 caracteres;
  `bitcoin` sempre nulo. A aplicação normaliza o EVM para minúsculas antes de
  gravar.
- Ativo repetido recusado, inclusive o nativo (`NULLS NOT DISTINCT`), e
  aceito em outro usuário.
- Movimento só em conta do mesmo usuário e do tipo `cripto`
  (`movimento_cripto_conta_cripto_do_usuario`, pela coluna gerada `tipo_conta`
  e a FK para `conta (id, usuario_id, tipo)`, a técnica da `V10`) e só com ativo
  do mesmo usuário (`movimento_cripto_ativo_do_usuario`). Mesma coisa em
  `cotacao_ativo_do_usuario`.
- **Lançamento recusado em conta cripto, sem gatilho.** FK composta só exige
  igualdade, então `conta` ganhou `aceita_lancamento` (gerada,
  `tipo <> 'cripto'`) com `unique (id, usuario_id, aceita_lancamento)`, e
  `lancamento` ganhou `conta_aceita_lancamento` (gerada, sempre `true`) com a FK
  `lancamento_conta_aceita_lancamento`. O `INSERT` não cita as duas colunas. O
  custo foi uma reescrita de `lancamento` sob lock, como na `V11`, e um boolean
  por linha. A mesma FK também recusa trocar para `cripto` o tipo de uma conta
  que já tem lançamento.
- Apagar conta ou ativo que tenha movimento é recusado (`NO ACTION`, como na
  `V6`). Apagar o usuário limpa tudo no cascade.

**Fica com a aplicação:**

- Quantidade com no máximo as casas decimais do ativo: depende de outra tabela,
  e CHECK não olha tabela.
- Venda não deixar a posição negativa, e apagar compra que deixaria alguma data
  negativa: a escrita trava a linha da conta com `FOR UPDATE` e confere a soma
  (ADR-035).
- `cotacao.fonte` só aceita `manual` por enquanto.

**Índice da F13.** `movimento_cripto_usuario_conta_data (usuario_id, conta_id,
data)` serve as três leituras de movimento. Medido num Postgres 17 descartável
com 205 mil movimentos (5 mil do usuário testado, 2 mil cotações):

| Query | Antes | Depois |
| --- | --- | --- |
| Posição de um ativo numa conta até uma data | Parallel Seq Scan, 10,6 ms | Bitmap Index Scan, 0,35 ms |
| Lista de movimentos da conta por data, 50 linhas | Parallel Seq Scan e sort, 7,0 ms | Index Scan Backward, 0,06 ms |
| Patrimônio do usuário até uma data | Parallel Seq Scan, 7,5 ms | Bitmap Index Scan, 0,64 ms |

A última cotação até um momento (`WHERE ativo_id = ? AND momento <= ? ORDER BY
momento DESC LIMIT 1`) usa a PK `(ativo_id, momento)` lida de trás para frente
(Index Scan Backward, 0,02 ms), então `cotacao` não ganha índice descendente. A
linha `create index on cotacao (ativo_id, momento desc)` de "Índices iniciais"
não é necessária.

### Renda fixa

Recorte da F13.2 (ADR-037), migration `V14`. LCI, LCA e CDB digitados, com o
valor calculado pelos índices do Banco Central e corrigível pela marcação.

```
conta.tipo ganha 'investimento'   -- saldo_inicial 0, não recebe lançamento
conta.recebe_lancamento bool generated (tipo not in ('cripto','investimento'))
  -- alvo da FK lancamento_conta_recebe_lancamento; aceita_lancamento da V13
  -- continua valendo e ficou redundante

titulo_renda_fixa
  id              uuid pk (sem DEFAULT)
  usuario_id      uuid fk not null on delete cascade
  conta_id        uuid not null     -- FK composta: conta do mesmo usuário, tipo investimento
  tipo            text not null     -- lci | lca | cdb
  nome            text not null     -- 1 a 100, sem espaço nas pontas
  valor_aplicado  numeric(18,2) not null   -- > 0 e até 90071992547409.91
  data_aplicacao  date not null
  vencimento      date null         -- depois da aplicação
  indexador       text not null     -- prefixado | cdi | ipca
  taxa            numeric(9,4) not null    -- > 0; % a.a., % do CDI ou % a.a. acima do IPCA
  data_resgate    date null         -- junto com valor_resgate, não antes da aplicação
  valor_resgate   numeric(18,2) null
  criado_em       timestamptz not null default now()
  tipo_conta      text generated always as ('investimento') stored
  unique (id, usuario_id)

marcacao_renda_fixa                 -- o valor que o banco mostrou
  usuario_id  uuid fk not null on delete cascade
  titulo_id   uuid not null         -- FK composta com o título, on delete cascade
  data        date not null
  valor       numeric(18,2) not null    -- >= 0
  primary key (titulo_id, data)

indice                              -- dado público do Banco Central (SGS)
  serie  text not null              -- cdi (série 12, % ao dia) | ipca (433, % ao mês)
  data   date not null
  valor  numeric(12,8) not null     -- o percentual publicado
  primary key (serie, data)
```

**`indice` não tem `usuario_id`** (desvio do "toda tabela tem `usuario_id`",
ADR-037): é a mesma série para todo mundo, sem dono, e nenhuma rota escreve
nela a partir do cliente. Só o job grava.

**Valor não é coluna.** O valor de um título numa data é calculado da última
marcação até a data (ou da aplicação) com o fator do indexador, como o saldo é a
soma dos lançamentos. Os dias úteis são os dias com CDI publicado.

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

Rascunho para a F24 (a F13 usa `movimento_cripto`, acima):

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
  intervalo       smallint not null default 1   -- a cada N, de 1 a 99
  dia_do_mes      smallint null        -- 1..31, só em mensal
  dia_da_semana   smallint null        -- 0 (domingo) a 6, só em semanal
  inicio          date not null
  fim             date null
  ocorrencias_max int null             -- 1..10000
  check (fim is null or ocorrencias_max is null)
```

**Desvio do modelo anterior (ADR-026):** `dia_do_mes` é um `smallint` escalar, não
o array `dias_do_mes`. A F5 aceita um dia por regra, e os vários dias de um
salário dividido ficam em `template_divisao` (F6).

Migration `V8` (ADR-026):

- `regra_frequencia_valida`, `regra_intervalo_faixa` (1 a 99),
  `regra_dia_do_mes_faixa` (1 a 31), `regra_dia_da_semana_faixa` (0 a 6),
  `regra_ocorrencias_max_faixa` (1 a 10000: uma série diária de uns 27 anos, e
  um teto para a materialização não virar laço enorme).
- `regra_inicio_faixa` (1900 a 2199) e `regra_fim_faixa` (`fim` entre `inicio` e
  2199-12-31; `fim` igual a `inicio` vale uma ocorrência).
- Invariante 6: `regra_fim_ou_ocorrencias_max`.
- `regra_campos_conforme_frequencia` amarra os campos à frequência: `mensal`
  exige `dia_do_mes` e não tem `dia_da_semana`; `semanal` exige `dia_da_semana` e
  não tem `dia_do_mes`; `diaria` e `anual` não têm nenhum dos dois. Anual repete
  dia e mês do `inicio`, então não guarda campo próprio.
- `usuario_id` é `ON DELETE CASCADE`. `unique (id, usuario_id)`
  (`regra_id_usuario_unico`) é o alvo da FK composta do template. Sem índice
  por `usuario_id`: a regra é lida pelo template (`template_regra_unica`).

`dia_do_mes` 29, 30 ou 31 precisa de regra para mês curto. Decisão:
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

Migration `V8` (ADR-026): `valor_total` mantém o nome acima e tem as mesmas
faixas de `lancamento.valor` (`template_valor_total_faixa`, mais `template_titulo_*`
e `template_descricao_tamanho`, iguais às de lançamento). `tipo` aceita `receita`
e `despesa` (sem transferência recorrente). O dono é garantido pelo banco por FK
composta, `NO ACTION`: `template_conta_mesmo_usuario`
`(conta_id, usuario_id)`, `template_categoria_mesmo_usuario_e_tipo`
`(categoria_id, usuario_id, tipo)` e `template_regra_mesmo_usuario`
`(regra_id, usuario_id)`. `template_regra_unica`: uma regra por template.
`unique (id, usuario_id)` (`template_id_usuario_unico`) é o alvo da FK de
`lancamento`. `criado_em` tem `DEFAULT now()`. Índice `template_usuario
(usuario_id)`: lista de `GET /recorrencias` e a checagem das FKs de conta e
categoria no cascade da exclusão do usuário. O job que lista templates ativos
pagina pela chave primária e não precisa de índice próprio.

Migration `V9` em `lancamento` (ADR-027):

- `parcela_ordem smallint` nula (`lancamento_parcela_ordem_faixa`, 1 a 10) e
  `lancamento_parcela_so_com_template`: sem template não há parcela.
- `lancamento_template_ocorrencia_parcela_unica`: índice único parcial em
  `(template_id, ocorrencia_data, parcela_ordem) NULLS NOT DISTINCT WHERE
  template_id IS NOT NULL`. O `NULLS NOT DISTINCT` mantém a regra da `V8` para
  template sem divisão (duas sem parcela na mesma data colidem). O `WHERE`
  evita que lançamento avulso, com tudo nulo, colida com outro avulso. Parcelas
  dos dias 30 e 31 caem as duas em 28 de fevereiro e coexistem pela ordem. Não é
  constraint porque índice parcial não pode ser. Criado antes de o índice da
  `V8` cair, e o `CREATE` bloqueia escrita em `lancamento` só durante a
  varredura.
- Cláusula do `ON CONFLICT`: `ON CONFLICT (template_id, ocorrencia_data,
  parcela_ordem) WHERE template_id IS NOT NULL DO NOTHING`. Sem o `WHERE` o
  Postgres não infere o índice parcial e recusa o comando.

Migration `V11` em `lancamento` (ADR-032): compra parcelada. Nulo nas três
colunas é "não é parcela". Todos os `CHECK` entram `NOT VALID` e são validados
em seguida.

- Colunas: `parcela_de_id uuid`, `parcela_num smallint`, `parcela_total
  smallint`, nulas, e `parcela_tipo_conta text` gerada `STORED`
  (`'cartao_credito'` quando `parcela_de_id` não é nulo, nula no resto). O
  `INSERT` não cita a coluna gerada.
- `lancamento_parcela_colunas_juntas`: as três nulas ou as três preenchidas.
  `lancamento_parcela_total_faixa`: `parcela_total` de 2 a 48.
  `lancamento_parcela_num_faixa`: `parcela_num` de 1 a `parcela_total`.
- `lancamento_parcela_primeira_aponta_para_si`: `(parcela_de_id = id) =
  (parcela_num = 1)`. Só a primeira tem `parcela_num` 1, e ela é a que aponta
  para si.
- `lancamento_parcela_sem_template`: parcela sem `template_id` e sem
  `parcela_ordem`. `lancamento_parcela_so_despesa_realizada`: parcela só com
  `tipo = 'despesa'` e `status = 'realizado'`.
- **Dono, primeira e total numa FK só.** `lancamento_parcela_mesma_compra`: FK
  `(parcela_de_id, usuario_id, parcela_de_id, parcela_total)` para
  `lancamento (id, usuario_id, parcela_de_id, parcela_total)`, `NO ACTION`. O
  alvo precisa ter `id` igual ao `parcela_de_id` da parcela e também
  `parcela_de_id` igual a ele mesmo, então é uma primeira (com o `CHECK` acima,
  a parcela 1), do mesmo usuário e com o mesmo `parcela_total`. Parcela
  apontando para outra parcela que não é a primeira, para outro usuário ou com
  total diferente é recusada. O alvo é o `unique`
  `lancamento_parcela_id_usuario_origem_total_unico`; o unique de 4 colunas da
  V7 não serve porque a FK exige as mesmas colunas. A mesma conta em todas as
  parcelas da compra fica com a aplicação (a conta não se edita, ADR-032).
- **Delete.** `NO ACTION` checa no fim do comando: apagar a primeira com outras
  apontando para ela é recusado; `DELETE ... WHERE parcela_de_id = :id` apaga a
  compra inteira, porque a primeira apontar para si não conta quando ela também
  sai; uma parcela que não é a primeira se apaga sozinha. A exclusão do usuário
  limpa tudo no cascade.
- **Parcela só em cartão de crédito, sem gatilho.**
  `lancamento_parcela_conta_cartao`: FK `(conta_id, usuario_id,
  parcela_tipo_conta)` para `conta (id, usuario_id, tipo)`
  (`conta_id_usuario_tipo_unico`, V10). É `MATCH SIMPLE`: com a coluna gerada
  nula (não parcela) a FK não é checada. Também impede mudar o tipo de uma conta
  que tem parcelas. Custo medido, Postgres 17 descartável com 400 mil linhas:
  a coluna `STORED` reescreve `lancamento` sob lock exclusivo em 1,1 s; o
  `unique` novo levou 0,24 s e a validação das FKs menos de 0,05 s cada.
  Migration inteira por volta de 1,5 s. Aceito por ser uma vez.
- Índice `lancamento_parcela_de (parcela_de_id) WHERE parcela_de_id IS NOT
  NULL`: busca das parcelas da compra (`WHERE usuario_id = :userId AND
  parcela_de_id = :id`) e checagem da FK ao apagar a primeira. Medido com 640
  mil linhas (240 mil parcelas, compras de 12x), compra de 12 parcelas: sem
  índice 49 ms (Bitmap Scan por `lancamento_usuario_data_caixa` lendo 3200
  linhas do usuário e filtrando) e 37 ms só por `parcela_de_id` (seq scan
  paralelo); com índice 0,16 ms e 0,02 ms. `DELETE ... WHERE parcela_de_id`
  da compra: 63 ms sem índice, 0,86 ms com. Criado sem `CONCURRENTLY` porque o
  Flyway roda em transação (30 ms de varredura).
- Desfazer: bloco comentado no fim da `V11`. Apaga as parcelas.

### Divisão — o caso do salário em dois dias

```
template_divisao
  id            uuid pk
  usuario_id    uuid fk not null
  template_id   uuid fk not null
  dia_do_mes    smallint not null    -- 1..31
  percentual    numeric(5,2) null    -- 40.00; maior que 0 e menor que 100
  valor_fixo    numeric(18,2) null   -- 0.01 a 90071992547409.91
  ordem         smallint not null    -- 1..10
  check ((percentual is null) <> (valor_fixo is null))
  unique (template_id, dia_do_mes)
  unique (template_id, ordem)
```

Suporta os dois modos que você descreveu, e mais de dois dias:

- **Por percentual:** valor cheio R$ 5.000, dia 15 = 40%, dia 30 = 60%
- **Por valor:** dia 15 = R$ 2.000, dia 30 = R$ 3.000

Sem nenhuma linha de divisão, o template gera uma ocorrência por período.

**Regra do centavo:** em divisão por percentual, calcula-se cada parcela com
truncamento (`setScale(2, RoundingMode.DOWN)`) e o resto vai para a **última** parcela pela `ordem`. A soma das
parcelas é sempre igual ao `valor_total` — isso é invariante e precisa de teste
(ex.: R$ 1.000,00 em três partes de 33,33% não pode somar R$ 999,99).

Validação: soma dos percentuais = 100,00 (10000 em `percentualCentesimos` no
JSON), ou soma dos valores fixos = `valor_total`.

Migration `V9` (ADR-027), recorte da F6:

- Só frequência mensal, de 2 a 10 parcelas. A aplicação garante o modo único
  (todas as linhas do template em percentual, ou todas em valor fixo), a soma e
  o mínimo de 2; o banco garante cada linha e as unicidades.
- No JSON o percentual vai em centésimos de ponto percentual
  (`percentualCentesimos`: 40% é `4000`, 33,33% é `3333`) e o valor fixo em
  `valorCentavos`. No banco, `numeric(5,2)` e `numeric(18,2)`.
- Regra do centavo da F6: cada parcela por percentual é
  `valor_total × percentual / 100` truncada em 2 casas; a última pela `ordem`
  recebe `valor_total` menos a soma das outras.
- CHECKs: `divisao_percentual_faixa` (0 e 100 ficam de fora),
  `divisao_valor_fixo_faixa`, `divisao_dia_do_mes_faixa`, `divisao_ordem_faixa`,
  `divisao_percentual_ou_valor`. Únicos: `divisao_template_dia_unico` e
  `divisao_template_ordem_unica`, que também servem a leitura por `template_id`.
- O dono vem da FK composta `divisao_template_mesmo_usuario`
  `(template_id, usuario_id)`, `NO ACTION` como as demais: template com parcela
  não se apaga. `usuario_id` é `ON DELETE CASCADE`. Sem índice por `usuario_id`:
  a tabela é pequena e só o cascade da exclusão do usuário a varre.

### Ocorrências

```
lancamento
  id                uuid pk
  usuario_id        uuid fk not null
  tipo              text not null      -- receita | despesa | transferencia
  titulo            text not null
  descricao         text
  categoria_id      uuid fk null       -- nulo se e só se tipo = transferencia
  conta_id          uuid fk not null
  valor             numeric(18,2) not null  -- sempre positivo
  data_competencia  date not null
  data_caixa        date not null
  status            text not null      -- previsto | realizado | cancelado
  template_id       uuid fk null       -- de onde veio
  ocorrencia_data   date null          -- a data que a regra gerou; nula se e só se template_id é nulo
  parcela_ordem     smallint null      -- 1..10; a ordem da parcela da divisão, nula sem divisão
  desgarrado        bool not null default false
  parcela_de_id     uuid fk null       -- self-fk: a primeira parcela da compra; a primeira aponta para si
  parcela_num       smallint null      -- 1..parcela_total
  parcela_total     smallint null      -- 2..48
  parcela_tipo_conta text null gerada  -- 'cartao_credito' se parcela_de_id não é nulo; a app não escreve
  hash_externo      text null          -- FITID do OFX, ou hash dos campos
  importacao_linha_id uuid fk null
  criado_em         timestamptz not null
  unique nulls not distinct (template_id, ocorrencia_data, parcela_ordem) where template_id is not null
  unique (usuario_id, hash_externo)
```

Migration `V6` (ADR-024) cria só o recorte da F3: `id`, `usuario_id`, `tipo`,
`titulo`, `descricao`, `categoria_id`, `conta_id`, `valor`, `data_competencia`,
`data_caixa`, `status`, `criado_em`. A `V7` acrescenta a transferência e a `V8` o recorte da F5, abaixo.
A `V9` acrescenta `parcela_ordem` e troca a chave de idempotência (abaixo).
A `V11` acrescenta a compra parcelada (abaixo).
As demais colunas e tabelas deste bloco chegam com as suas fatias.

- `tipo` aceita `receita`, `despesa` e `transferencia` (V7). `categoria_id` é
  nulo se e somente se o tipo é `transferencia`
  (`lancamento_categoria_conforme_tipo`, `(tipo = 'transferencia') = (categoria_id
  IS NULL)`). A FK de categoria é `MATCH SIMPLE`: com `categoria_id` nulo ela não
  é checada, então a perna passa, e os outros tipos são obrigados a ter categoria
  pelo `CHECK`. `status` tem `DEFAULT 'realizado'` e, desde a `V8`, aceita
  `previsto`, `realizado` e `cancelado`.
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
- Índice da F10 (cartão), medido e **não criado**: 400 mil linhas em 21
  usuários, um com 20 mil lançamentos em 830 dias e 2 mil no cartão. A fatura lê
  `lancamento` por `usuario_id`, `conta_id` e intervalo de `data_caixa`. Com só
  `lancamento_usuario_data_caixa`: 12 meses (864 linhas do cartão, 8760 lidas)
  2,3 ms; um mês (72 do cartão, 720 lidas) 0,14 ms. Com
  `(usuario_id, conta_id, data_caixa)` experimental: 0,56 ms e 0,05 ms. Ganho de
  menos de 2 ms por consulta, pago em toda escrita de lançamento. Quem medir
  lentidão real na lista de faturas cria o índice com o `EXPLAIN` dela.
  Conferido com a consulta real da rota (mesma massa, 12 meses): o total por
  `data_caixa` usa `lancamento_usuario_data_caixa` (Bitmap Index Scan, 8748 linhas
  lidas, 853 do cartão) em 1,9 ms; os pagamentos entram por
  `lancamento_id_usuario_tipo_conta_unico` a partir de `transferencia` em 0,12 ms.
- Query do saldo (a partir da F4): `saldo_inicial` + receitas − despesas +
  entradas − saídas, só `realizado`. A direção da perna vem de um `LEFT JOIN`
  em `transferencia` por `entrada_id`; sem ligação de entrada, a perna é saída.
  Filtrar `te.usuario_id = l.usuario_id` no `ON` deixa o planejador usar
  `transferencia_usuario` em vez de ler a tabela toda.

```sql
WITH movement AS MATERIALIZED (
  SELECT l.conta_id,
         SUM(CASE l.tipo
               WHEN 'receita' THEN l.valor
               WHEN 'despesa' THEN -l.valor
               WHEN 'transferencia' THEN CASE WHEN te.id IS NOT NULL THEN l.valor ELSE -l.valor END
             END) AS movimento
  FROM lancamento l
  LEFT JOIN transferencia te ON te.entrada_id = l.id AND te.usuario_id = l.usuario_id
  WHERE l.usuario_id = :userId AND l.status = 'realizado'
  GROUP BY l.conta_id
)
SELECT c.id, c.saldo_inicial + COALESCE(m.movimento, 0) AS saldo
FROM conta c LEFT JOIN movement m ON m.conta_id = c.id
WHERE c.usuario_id = :userId;
```

- Lista do mês com `transferenciaId` e `direcao` (nulos fora de transferência):

```sql
SELECT l.id, l.tipo, l.titulo, l.descricao, l.valor, l.categoria_id, l.conta_id,
       l.data_competencia, l.data_caixa, l.status, l.criado_em,
       COALESCE(ts.id, te.id) AS transferencia_id,
       CASE WHEN ts.id IS NOT NULL THEN 'saida'
            WHEN te.id IS NOT NULL THEN 'entrada' END AS direcao
FROM lancamento l
LEFT JOIN transferencia ts ON ts.saida_id = l.id
LEFT JOIN transferencia te ON te.entrada_id = l.id
WHERE l.usuario_id = :userId AND l.data_caixa >= :from AND l.data_caixa < :to
ORDER BY l.data_caixa DESC, l.criado_em DESC, l.id DESC;
```

  Medido com 480 mil lançamentos e 40 mil transferências: mês de um usuário
  (76 linhas) em 0,8 ms, com as duas junções por `index scan` nos `UNIQUE`.
  Busca da transferência: `WHERE usuario_id = :userId AND id = :id`, pela chave
  primária.
- Relatório de receita ou despesa exclui `tipo = 'transferencia'`.

Pontos que importam:

- **`valor` sempre positivo.** O sinal vem do `tipo`. Valor negativo mais tipo
  gera dupla negação e bug de relatório.
- **`status`**: `previsto` é o futuro gerado pela recorrência; `realizado` é o
  que aconteceu. Saldo soma só `realizado`; saldo projetado soma os dois.
- **`desgarrado`**: você editou essa ocorrência. A materialização nunca
  sobrescreve linha desgarrada. É o que permite "em março veio com bônus".
- **`parcela_de_id`**: compra em 12x gera 12 linhas apontando para a primeira
  (a primeira aponta para si), com `parcela_num` 1..12 e a mesma
  `data_competencia`, mas `data_caixa` avançando um mês por parcela. Garantias
  do banco na seção "Migration `V11`" abaixo.

A materialização roda em lote e gera previstos **12 meses à frente**, sendo
idempotente: rodar duas vezes não duplica. A chave de idempotência é
`(template_id, ocorrencia_data)` em **toda** linha com template, desgarrada ou
não (ADR-026). Com a chave em `data_competencia`, editar a data de uma
ocorrência deixaria a regra gerar outra no dia antigo; `ocorrencia_data` guarda
"qual ocorrência da série" e nunca muda. Desde a `V9` (ADR-027) a chave é
`(template_id, ocorrencia_data, parcela_ordem)`.

Migration `V8` em `lancamento` (ADR-026):

- Colunas novas: `template_id` e `ocorrencia_data` nulas (nulo é "avulso") e
  `desgarrado boolean not null default false`. O `DEFAULT` constante entra só no
  catálogo no Postgres 11+, sem reescrever a tabela. Os `CHECK` novos entram
  `NOT VALID` e depois `VALIDATE`, como na `V7`.
- `lancamento_template_mesmo_usuario`: FK `(template_id, usuario_id)` para
  `template_lancamento (id, usuario_id)`, `NO ACTION`. Template com ocorrência não
  se apaga; desativar apaga só as previstas.
- `lancamento_template_ocorrencia_unica`: `unique (template_id, ocorrencia_data)`.
  O Postgres ignora nulo em `unique` comum, então lançamento avulso não colide. É
  o alvo do `ON CONFLICT (template_id, ocorrencia_data) DO NOTHING` da
  materialização e o índice da FK e da desativação. A `V9` o substitui.
- `lancamento_template_e_ocorrencia_juntos`: `(template_id IS NULL) =
  (ocorrencia_data IS NULL)`. `lancamento_desgarrado_so_com_template`.
  `lancamento_ocorrencia_data_faixa` (1900 a 2199).
- `lancamento_transferencia_realizada_sem_template`: transferência só nasce
  `realizado` e a perna não tem template.
- Sem índice novo. Medido com 400 mil lançamentos mais 120 mil previstos (200
  usuários, 50 templates com 12 previstas cada): saldo de um usuário com 2 mil
  realizados passou de 2,6 ms para 2,7 ms (o filtro `status` remove as 600
  previstas depois do índice); lista de um mês de 0,15 ms para 0,22 ms; desativar
  (`DELETE` por `usuario_id`, `template_id`, `status`, `ocorrencia_data`) 1 ms
  pela unique nova.
- A `V8` também fixa `search_path = public, pg_temp` no gatilho da `V7`
  (`transferencia_perna_em_dois_papeis`) com `ALTER FUNCTION`.

### Transferência

Transferência não é receita nem despesa. Esquecer isso duplica o patrimônio.

```
transferencia
  id                uuid pk
  usuario_id        uuid fk not null
  saida_id          uuid fk not null unique    -- lancamento na conta origem
  entrada_id        uuid fk not null unique    -- lancamento na conta destino
  conta_saida_id    uuid not null              -- conta da perna de saída
  conta_entrada_id  uuid not null              -- conta da perna de entrada
  tipo              text                       -- gerada, sempre 'transferencia'; não se insere
```

Duas linhas de `lancamento` com `tipo = transferencia`, ligadas. Todo
relatório de receita ou despesa **exclui** esse tipo. A perna não tem categoria
(`categoria_id` nulo) e a direção vem da ligação: a que está em `saida_id`
subtrai do saldo, a que está em `entrada_id` soma (ADR-025).

Pagamento de fatura de cartão é uma transferência: sai da conta corrente, entra
na conta do cartão.

Migration `V7` (ADR-025). O que o banco garante, e como:

- **Dono, tipo e conta da perna, sem gatilho.** Duas FKs compostas
  (`transferencia_saida_perna_valida`, `transferencia_entrada_perna_valida`)
  de `(perna, usuario_id, tipo, conta da perna)` para `lancamento (id,
  usuario_id, tipo, conta_id)`, alvo `lancamento_id_usuario_tipo_conta_unico`.
  `tipo` é coluna gerada constante, só para a FK carregar `'transferencia'`.
  Perna de outro usuário, perna que é receita ou despesa, ou em conta diferente
  da declarada, o banco recusa. Mudar a `conta_id` de uma perna existente também.
- **Contas diferentes (invariante 7), sem gatilho.** `conta_saida_id` e
  `conta_entrada_id` existem só para isso, com `CHECK` de diferentes
  (`transferencia_contas_diferentes`) e as FKs acima ligando-as às pernas.
  A aplicação grava as mesmas contas que gravou nas pernas.
- **Perna diferente.** `transferencia_pernas_diferentes`: `saida_id <> entrada_id`.
- **Cada lançamento em no máximo uma transferência.** `transferencia_saida_unica`
  e `transferencia_entrada_unica` cobrem o mesmo papel duas vezes. O caso do
  mesmo lançamento como saída de uma e entrada de outra um `UNIQUE` não vê, e é
  o único ponto com **gatilho**: `transferencia_perna_em_dois_papeis`, `BEFORE
  INSERT OR UPDATE OF saida_id, entrada_id`. Trava as pernas com `FOR UPDATE`
  (ordem de id) antes de conferir, para duas transações simultâneas não passarem
  juntas. Erro com `unique_violation` e constraint com esse nome.
- **Delete.** As FKs para `lancamento` são `NO ACTION`. Apagar a `transferencia`
  não apaga lançamento. Apagar uma perna com a ligação viva é recusado. A
  aplicação apaga **primeiro a transferencia, depois as duas pernas**, na mesma
  transação. Apagar o usuário limpa as três tabelas em cascata.
- **Não garantido pelo banco:** valor e datas iguais nas duas pernas. A aplicação
  garante (ADR-025) e a perna não se edita por `/lancamentos`.
- Insert, na ordem: as duas pernas em `lancamento` (sem categoria), depois a
  `transferencia`. As FKs checam no fim do comando.

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
4. Despesa em conta de benefício com lista só com categoria permitida **no momento da escrita** (V12, ADR-034, trigger `lancamento_categoria_permitida_no_beneficio`): encolher a lista não mexe em despesa já gravada. Receita não é restrita; a lista aceita só conta de benefício e categoria de despesa do mesmo usuário (FK composta)
5. Compra parcelada (V11, ADR-032): `parcela_num` entre 1 e `parcela_total`, `parcela_total` entre 2 e 48, as três colunas juntas, parcela só em cartão, só despesa realizada, e toda parcela aponta para uma primeira do mesmo usuário e do mesmo total (FK composta)
6. `regra_recorrencia`: `fim` e `ocorrencias_max` não coexistem
7. Transferência: `saida_id` e `entrada_id` em contas diferentes (V7, `CHECK` mais FKs compostas)
8. `hash_externo` único por usuário (deduplicação de importação)
9. `hash_arquivo` único por usuário (mesmo arquivo não reimporta)
10. `hash_tx` único por posição (mesma transação não duplica)
11. `carteira_observada`: endereço único por usuário e rede
12. `posicao_cripto` com `origem = 'sincronizada'` não aceita escrita manual (F24; a F13 não tem a tabela)
13. Cripto (V13, ADR-035): conta `cripto` com `saldo_inicial` 0 e sem lançamento; ativo único por usuário, rede e contrato; contrato no formato da rede; movimento só em conta cripto e ativo do mesmo usuário, com quantidade e custo positivos; cotação só de ativo do mesmo usuário, com preço positivo e um valor por momento

## Índices iniciais

Só o que tem query real, conforme `stacks/sql.md`:

```sql
create index on lancamento (usuario_id, data_caixa);
create index on lancamento (usuario_id, data_competencia, categoria_id);
create index on transferencia (usuario_id);
create index on lancamento (template_id) where template_id is not null;
create index on lancamento (parcela_de_id) where parcela_de_id is not null;
create index on cotacao (ativo_id, momento desc);
create index on lancamento (usuario_id, hash_externo) where hash_externo is not null;
create index on importacao_linha (importacao_id, decisao);
```

Na `V6` só o primeiro existe e a `V7` acrescenta o de `transferencia`; os outros chegam com as fatias que têm a query. A `V10` não acrescenta nenhum. A `V11` cria o parcial em `parcela_de_id`. A `V12` não acrescenta nenhum (a PK de `conta_categoria_permitida` serve, conferido com `EXPLAIN`). A `V13` cria `movimento_cripto (usuario_id, conta_id, data)` e dispensa o índice descendente de `cotacao`, porque a PK serve (seção Cripto).
O primeiro serve saldo e fluxo de caixa; o segundo, gráfico por categoria e
orçamento. Antes de adicionar qualquer outro, rode `EXPLAIN ANALYZE` e mostre o
antes e o depois.
