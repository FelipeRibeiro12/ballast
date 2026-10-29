# ADR-073: Importação de PDF de fatura (F26)

**Status:** aceito
**Data:** 2026-10-29
**Detalha:** ADR-008, ADR-069, ADR-070

## Contexto

O ADR-008 deixou o PDF de fatura por último e como melhor esforço: vai falhar
com alguma frequência, e a resposta certa a isso é revisão obrigatória, não
um parser por banco. A F22 e a F23 já entregam a máquina de revisão. A F26
acrescenta só um leitor de PDF e as regras de cartão.

## Decisão

**Dependência.** Apache PDFBox 3.0.8 (Apache-2.0, mantido pela Apache, última
versão estável na data). Só extração de texto: sem OCR, sem renderização, sem
executar JavaScript nem abrir anexo. `commons-logging` fica de fora, porque o
`spring-jcl` já fornece a mesma API. Alternativas descartadas: iText (AGPL ou
licença paga), OpenPDF (menos recursos de extração), parser à mão (PDF não é
legível sem decodificar fluxos e fontes).

**Envio.** `POST /importacoes/pdf?conta_id=<uuid>`, corpo cru
(`application/octet-stream`), como o OFX: sem multipart, o nome do arquivo nunca
chega ao servidor e nada vai a disco. A conta precisa ser `cartao_credito`
(senão 400). Mesma revisão, confirmação, descarte, dedupe de arquivo (SHA-256),
teto de 20 pendentes e limite de 10 por minuto por IP do OFX.

**PDF é entrada não confiável.** Teto de 5 MiB (413, `BodyLimitFilter` com
exceção só para essa rota), 30 páginas, 1 milhão de caracteres de texto e 10
segundos (a extração roda numa thread virtual; no estouro a thread é
abandonada, porque o PDFBox não olha interrupção). Cabeçalho `%PDF-` nos
primeiros 1024 bytes. PDF com senha ou criptografado, mesmo só com senha de
dono, é recusado. Cache de fluxos só em memória, nunca arquivo temporário, e o
cache de fontes do PDFBox vai para o diretório temporário. Todo erro é 422 com
mensagem fixa, sem trecho do arquivo nem texto da biblioteca. Sem texto
(imagem escaneada) ou sem nenhuma transação reconhecida: 422.
O texto sai por um `Writer` com teto (1 milhão de caracteres, conferido durante
a extração) e o cache de fluxos decodificados do PDFBox é limitado a 64 MiB só
em memória (`MemoryUsageSetting.setupMainMemoryOnly`). No máximo 3 extrações
ao mesmo tempo, contando as threads abandonadas no timeout (semáforo liberado
quando a thread termina); cheio, o envio é recusado com 422. Risco residual: o
parse do PDF (xref, objetos) roda antes dos tetos e um fluxo que expande fora
do cache pode gastar heap; o teto de memória da JVM em produção é a última
defesa, e o Dockerfile mata o processo em OOM (auditoria security, ALTO 1 e 2
mitigados, residual no backlog).

**Leitor por banco.** Interface `BankStatementReader` (`recognizes`, `read`).
`PdfParser` testa a lista na ordem e usa o primeiro que reconhece; hoje só há o
`GenericStatementReader`. Acrescentar um emissor é uma classe nova na lista.

**Heurística genérica**, linha a linha: `dd/MM`, `dd/MM/yy` ou `dd/MM/yyyy`,
descrição, valor no fim (`1.234,56`, com `R$` opcional). `CR` ou sinal de menos
marca crédito (estorno, entra como receita). O valor vai de String para
`BigDecimal` pelo mesmo `CsvAmount` do CSV (2 casas no máximo, zero inválido).
Sem ano, vale o ano da referência (a maior data cheia do texto que não passe de
60 dias à frente de hoje, em geral o vencimento; sem ela, hoje), e uma data
depois da referência cai no ano anterior (compra de dezembro numa fatura de
janeiro).

**Linha não reconhecida não some.** Linha que começa com data ou termina em
valor com centavos, e não casou, entra como linha inválida (`erro: linha não
reconhecida`, só aceita `ignorar`), com o texto cru no título e na `bruta`.
Cabeçalho e rodapé sem data nem valor são descartados, para a revisão não
virar centenas de linhas de ruído. Máximo 2000 linhas. Crédito cuja descrição
fala em pagamento (`pagamento`, `pagto`, `pgto`) é inválido, `pagamento da
fatura: registre como transferência`: virar receita contaria o pagamento duas
vezes, porque a transferência da conta de pagamento já existe.

**Datas e parcelas.** A data lida é a da compra e vira `data_competencia`; a
`data_caixa` sai da regra do cartão (ADR-031) no mesmo serviço do lançamento
manual. Parcela `n/m` (sufixo da descrição, `PARC` opcional, `m` de 2 a 48)
fica no texto e em `bruta.parcela`. Cada linha vira um lançamento comum: a
importação **nunca cria compra parcelada** (ADR-032), então não duplica uma
compra que o usuário já lançou. Se ele já tem a compra parcelada, a conciliação
(mesmo valor, data a até 3 dias) é quem a reconhece; quando a fatura mostra uma
data diferente da compra original, a parcela vem como "importar" e o usuário
decide na revisão.

**`hash_externo`** (SHA-256 com tag `pdf:`): conta, data, valor com sinal,
descrição normalizada (trim, espaços colapsados, minúsculas) e ordinal entre
linhas iguais nessas chaves. Reenviar a fatura, ou outra com as mesmas compras,
cai em `ja_importada`.

**Texto de célula.** Mesma neutralização de fórmula do CSV (apóstrofo na
frente de `=`, `+`, `-`, `@`, TAB, CR). Controle vira espaço, título em 100
code points.

**Log.** Só ids e contagens.

## Consequência

Fatura em duas colunas, descrição quebrada em duas linhas ou valor na linha de
baixo não são lidos e aparecem como linha para ignorar. É o esperado do melhor
esforço. Se virar fonte de frustração, o ADR-008 manda remover o recurso em vez
de melhorar o parser de cada banco. A tela avisa antes do envio que é melhor
esforço, que pode falhar e que a revisão é obrigatória. O PDFBox traz cerca de
15 MB de jars.

## Detalhes de implementação

- **Web.** Terceiro formato no seletor da tela Importar extrato (`ImportarPdf`),
  só com cartões não arquivados. Mensagens saem do status, nunca do corpo.
- **Fuso.** "Hoje" para o ano é `America/Sao_Paulo`.
