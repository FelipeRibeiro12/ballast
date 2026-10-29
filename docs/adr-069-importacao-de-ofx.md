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
2. `hash_externo` = SHA-256 de `conta_id|FITID`. O FITID só é único dentro da
   conta, então a conta entra no hash. Sem FITID, o hash usa data, valor, texto
   e a ordem da ocorrência no arquivo. Linha cujo hash já está em `lancamento`
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
