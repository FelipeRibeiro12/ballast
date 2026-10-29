# ADR-069: Importação de OFX (F22)

**Status:** aceito
**Data:** 2026-10-29
**Detalha:** ADR-008

## Contexto

A F22 entrega a primeira importação: o usuário envia um extrato OFX, revisa o
que o app propõe por linha e confirma. O ADR-008 fixou o desenho (duas etapas,
dedupe, conciliação, arquivo não guardado). Faltam as escolhas de borda.

## Decisão

**Envio.** `POST /importacoes?conta_id=<uuid>` com o arquivo como corpo cru
(`application/octet-stream`), não multipart. Sem multipart não existe nome de
arquivo, `Content-Disposition` nem parser de partes: o nome nunca chega ao
servidor e não pode compor caminho. Nada vai a disco; o arquivo vive em memória
só durante o parse. Teto de 1 MiB e 2000 transações por arquivo. O
`BodyLimitFilter` ganha exceção só para essa rota (1 MiB, contado na leitura,
com a mesma recusa 413). Acima do teto de linhas: 422.

**Parser próprio, sem XML.** OFX 1.x é SGML e 2.x é XML, mas só as tags
`STMTTRN` e filhas interessam. Um leitor de tags à mão serve aos dois e não
expande entidade nenhuma, então XXE não tem por onde entrar. Arquivo com
`<!DOCTYPE` ou `<!ENTITY` é recusado (422). Sem dependência nova.
Codificação: UTF-8 se válido, senão Windows-1252 (padrão dos bancos
brasileiros). Campos lidos: `TRNTYPE`, `DTPOSTED`, `TRNAMT`, `FITID`, `NAME`,
`MEMO`, `CHECKNUM`, cada valor cortado em 200 caracteres. `TRNAMT` aceita
ponto ou vírgula, com no máximo 2 casas (`BigDecimal`, sem arredondar); linha
fora disso entra marcada como inválida e fica de fora da confirmação.

**Tabelas (V34).** `importacao` (conta, `hash_arquivo` SHA-256 do conteúdo,
status `pendente`/`confirmada`, totais, `criado_em`) com unique
`(usuario_id, hash_arquivo)`. `importacao_linha` com a linha crua em `jsonb`,
`hash_externo`, data, tipo, valor, título, proposta, decisão, vínculo e
resultado. Todas com `usuario_id` e FK composta com conta, categoria e
lançamento. `lancamento` ganha `hash_externo` e `importacao_linha_id`, com
unique parcial `(usuario_id, hash_externo)`.

**Dedupe, em ordem.**
1. Mesmo `hash_arquivo`: 409 com o `importacao_id` existente. Descartar a
   importação pendente libera o hash.
2. `hash_externo` = SHA-256 de `fitid:conta_id|FITID`. O FITID só é único dentro da
   conta, então a conta entra no hash. Sem FITID, o hash usa data, valor, texto
   e a ordem da ocorrência no arquivo, com a tag `nofitid:` na frente do texto. Linha cujo hash já está em `lancamento`
   (importada ou conciliada antes) vira proposta `ignorar`, motivo
   `ja_importada`. É isso que cobre extratos com dias sobrepostos.
3. Conciliação: lançamento da mesma conta, mesmo tipo, valor igual
   (`compareTo`), `data_competencia` a até 3 dias, sem `hash_externo`, não
   proposto a outra linha da mesma importação. Vale o de data mais próxima, um
   para um. Proposta `conciliar`.
4. Sobra: proposta `importar`.

**Revisão.** `PUT /importacoes/{id}/linhas/{linhaId}` muda a decisão
(`importar`, `ignorar`, `conciliar` com `lancamento_id`), e para `importar`
aceita `categoria_id` e `titulo`. A categoria padrão é "Outros" (despesa) ou
"Outras receitas" (receita). Sem decisão explícita vale a proposta.
`GET /importacoes`, `GET /importacoes/{id}` e `DELETE /importacoes/{id}`
(descarta pendente) completam a API. Todas filtram por `usuario_id` e `id`;
recurso de outro usuário é 404.

**Confirmação.** `POST /importacoes/{id}/confirmacao`, sem corpo, com
`Idempotency-Key` (entra em `IdempotencyFilter.ROUTES`, ADR-056). Roda numa
transação com a importação travada (`FOR UPDATE`). `importar` cria o lançamento
pelo mesmo serviço do `POST /lancamentos`, então as regras de conta, categoria,
cartão e as duas datas valem iguais (`data_competencia` é o `DTPOSTED`;
`data_caixa` sai da regra da conta). `conciliar` só grava `hash_externo` e o
vínculo no lançamento existente e **não cria lançamento**. `ignorar` não grava
nada. Linha cujo hash apareceu no meio-tempo vira `duplicada`, sem erro. Repetir
a confirmação numa importação confirmada é 409. Nada entra no extrato antes
dela.

**Valor no JSON.** Centavos inteiros (`valor`) mais `tipo`. Conversão com
`longValueExact()` na borda.

**Limite por usuário** (ADR-055): até 20 importações pendentes
(`LIMITE_IMPORTACOES_PENDENTES`).

**Log.** Nunca registra conteúdo de linha, memo ou FITID. Só ids e contagens.

## Alternativas descartadas

- Multipart: traz nome de arquivo e parser de partes sem ganho.
- Parser XML com DTD desligada: depende de configuração certa em cada versão;
  não ler entidade nenhuma é mais seguro e o OFX 1.x nem é XML.
- Biblioteca de OFX: dependência a mais para ler seis tags.
- Guardar a decisão no corpo da confirmação: estoura o teto de 4 KiB do corpo
  com centenas de linhas.

## Consequência

Importação pendente esquecida ocupa linhas no banco. Ela é limitada a 20 por
usuário e ganha limpeza por idade no backlog.
Cartão de crédito vai pelo mesmo caminho do `POST /lancamentos`, mas a F26
(fatura) é quem trata o vencimento da fatura como destino da linha.

## Detalhes de implementação

Escolhas da F22 que o texto acima deixou abertas.

- **Rotas e formato.** O parâmetro do envio é `conta_id`, como no ADR. O envio
  devolve 201 com o detalhe e as linhas. A confirmação devolve só as contagens
  (`criadas`, `conciliadas`, `ignoradas`, `duplicadas`). `GET /importacoes`
  traz as 100 mais novas, sem linhas.
- **Parser.** `DTPOSTED` vale como escrito (8 primeiros dígitos), sem converter
  fuso. Com vírgula e ponto juntos, o último separador é o decimal. `2.50` e
  `2.500` valem 2,50 (zeros à direita não contam como casa); `2.505` é inválida.
  Valor zero ou acima do teto da coluna também é linha inválida. Só as cinco
  entidades nomeadas do XML são desfeitas.
- **Título.** `NAME` vira título e `MEMO` vira descrição se for diferente; sem
  `NAME`, o `MEMO` é o título. Sem nenhum dos dois, "Sem descrição".
- **Hash sem FITID.** Usa a conta, a data, o valor com sinal, nome, memo e a
  ordem da ocorrência entre linhas idênticas. FITID repetido dentro do arquivo
  deixa a segunda linha em `ignorar`, motivo `duplicada_no_arquivo`.
- **Conciliação.** Só lançamento `realizado`: conciliar uma ocorrência prevista
  deixaria o hash numa linha que continua atrasada. O pareamento é global, do
  par mais próximo ao mais distante, e desempata por ordem do arquivo.
- **Linha que a proposta ignorou** (inválida, repetida, já importada) só aceita
  `ignorar` no `PUT`. Importar de novo seria duplicar.
- **Confirmação.** Uma trava consultiva por usuário serializa confirmações, para
  dois arquivos com o mesmo FITID não baterem no unique parcial. Primeiro vêm as
  conciliações, em ordem de id do lançamento, e depois as criações; é a ordem do
  ADR-057 (lançamento antes da conta). Se o lançamento a conciliar sumiu ou já
  tem hash, a linha vira lançamento novo, porque a transação do banco existe.
  Uma recusa de regra (conta arquivada, categoria fora da lista do benefício,
  saldo fora da faixa) desfaz a confirmação inteira com 400 e a importação segue
  pendente.
- **Apagar conta** apaga as importações dela antes (a FK é `NO ACTION`).
- **Teto de pendentes.** Conta só a `pendente`; a confirmada libera a vaga.
- **Tag no hash.** `fitid:` e `nofitid:` entram no texto que vai para o SHA-256,
  não no resultado, porque a coluna só aceita 64 hexa minúsculos. Sem a tag, um
  FITID forjado com o formato da composição sem FITID colidia com o hash de
  outra linha. A feature ainda não foi para produção, então nenhum hash antigo
  precisou de migração.
- **Conciliação sem produto cartesiano.** O pareamento guloso indexa os
  candidatos por (tipo, valor) e por data, e para cada distância de 0 a 3 dias
  cada linha só olha as duas datas à sua volta. O resultado é o mesmo do
  pareamento de todos os pares (mais próximo primeiro, depois ordem do arquivo,
  data e id do candidato), sem materializar linhas x candidatos.
- **Custo antes do trabalho.** O teto de 20 pendentes é conferido sem trava logo
  depois da checagem de arquivo repetido, antes do parse. A conferência com
  trava dentro da transação continua como defesa final. Envio e confirmação têm
  limite de 10 por minuto por IP. A confirmação usa `pg_try_advisory_xact_lock`:
  se outra do mesmo usuário roda, responde 409 na hora, sem prender conexão. As
  linhas do envio entram em lote.
- **Conciliação que não pega (`stampEntry` devolve 0).** Se o lançamento sumiu,
  mudou de conta ou já recebeu outro hash entre a revisão e a confirmação, a
  linha vira `criada` em vez de `conciliada`, sem erro e sem aviso na resposta.
  É de propósito: a transação do banco é real e não pode sumir. O efeito visível
  é `criadas` maior e `conciliadas` menor que a revisão mostrava. Se isso
  incomodar, o caminho é um campo `rebaixadas` na confirmação.
- **`bruta` fica.** A coluna `bruta` (campos do OFX como lidos) permanece depois
  da confirmação, para auditoria (ADR-008): mostra de onde veio cada lançamento.
  A limpeza por idade das pendentes esquecidas e da `bruta` de importações
  confirmadas está no `docs/backlog.md`.
