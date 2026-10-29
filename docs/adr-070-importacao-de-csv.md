# ADR-070: Importação de CSV (F23)

**Status:** aceito
**Data:** 2026-10-29
**Detalha:** ADR-008, ADR-069

## Contexto

A F22 entregou a máquina de revisão para OFX. Muitos bancos só exportam CSV, e
cada um com separador, data, decimal e sinal próprios. A F23 acrescenta o CSV
como segunda porta de entrada, sem mexer na revisão, na confirmação nem no
dedupe de arquivo.

## Decisão

**Mapeamento salvo.** Tabela `mapeamento_csv` (V35), com `usuario_id`, nome
(banco), separador, formato de data, decimal, sinal, se tem cabeçalho e as
colunas de data, valor, descrição e identificador (opcional), em índice 0 a 49.
Nome único por usuário, até 20 por usuário (`LIMITE_MAPEAMENTOS_CSV`, ADR-055).
CRUD em `/mapeamentos-csv`; id de outro usuário é 404. Sem FK da importação para
o mapeamento: apagar o mapeamento não afeta importação já criada.

**Envio e prévia.** `POST /importacoes/csv?conta_id&mapeamento_id` e
`POST /importacoes/csv/previa` (mapeamento na query, nada gravado), os dois com
corpo cru como no ADR-069: 1 MiB, 2000 linhas de dados, 50 colunas, célula
cortada em 200 caracteres, NUL recusado, 10 por minuto por IP. Parâmetro
não declarado ou repetido é 400. UTF-8 se válido, senão Windows-1252. O arquivo
não é guardado e o nome nunca chega ao servidor.

**Parser à mão.** RFC 4180 (aspas, aspas duplas, quebra dentro de aspas, CRLF,
LF, CR, BOM). Aspas sem fechar derrubam o arquivo (422). Linha vazia é ignorada
e não conta no ordinal. Sem dependência nova.

**Valor.** String direto para `BigDecimal`, nunca `double`. Com `virgula`, o
ponto é milhar e a vírgula é decimal (`1.234,56`); com `ponto`, o inverso.
Milhar só vale em grupos de três. No máximo 2 casas, sem arredondar. Zero,
parênteses e notação científica são inválidos. `R$` e espaços são aceitos. O
sinal decide o tipo: `negativo_despesa` ou `positivo_despesa` (fatura de
cartão). O valor guardado é o absoluto.

**Data.** Formato do mapeamento, `ResolverStyle.STRICT`, então 31/02 é inválida.
O `yyyy` do contrato vira `uuuu` por dentro, porque `yyyy` estrito exige a era.

**Linha ruim não derruba o arquivo.** Data ou valor ilegível, ou menos colunas
que o mapeamento, vira linha inválida (só aceita `ignorar`), como no OFX.

**Fórmula injetada.** Toda célula de texto (título, descrição, bruta) que
começa com `=`, `+`, `-`, `@`, TAB ou CR recebe um apóstrofo na frente, na
gravação. Nenhum lançamento nasce com fórmula crua, então qualquer exportação
futura já sai segura. Parse de data e de valor e a chave de hash usam a célula
limpa, para `-1.234,56` continuar sendo número. O front mostra o texto como veio
(texto do React, nunca HTML) e nunca monta CSV de volta.

**`hash_externo`** (SHA-256 de texto com tag, como no ADR-069):
- Com coluna de identificador e célula preenchida: `csvid:conta|id`. Id repetido
  no arquivo deixa a segunda linha em `ignorar`, `duplicada_no_arquivo`.
- Sem identificador: `csv:conta|data|valor normalizado|descrição normalizada|ordinal`.
  Descrição normalizada é trim, espaços colapsados e minúsculas (`Locale.ROOT`).
  O ordinal conta as linhas idênticas nessas três chaves, na ordem do arquivo.
  Dois cafés iguais no mesmo dia não colapsam, e o extrato reenviado ou
  sobreposto cai em `ja_importada`.
- O valor no hash é o normalizado (receita positiva), então o mesmo extrato
  mapeado com outro sinal gera outro hash. Aceito: trocar o sinal de um
  mapeamento depois de importar é raro e o dedupe de arquivo pega o reenvio.

**Título.** A descrição cortada em 100 code points; se passou, o texto inteiro
(até 500) vai em `descricao`. Vazia vira "Sem descrição".

**Log.** Nunca conteúdo de linha, só ids e contagens. Erro 422 tem mensagem
fixa, sem eco do arquivo.

## Alternativas descartadas

- Biblioteca de CSV: o formato é pequeno e a lista de limites (colunas, célula,
  linhas, NUL) fica mais clara no código próprio.
- Mapeamento embutido em cada envio: o usuário refaria a configuração a cada
  extrato.
- Neutralizar fórmula só na exibição: o dado cru ficaria no banco esperando a
  primeira exportação.
- Hash sem ordinal: dois lançamentos legítimos iguais no mesmo dia virariam um.

## Consequência

Mesmo extrato em dois bancos de formatos diferentes não se reconhece, porque o
hash depende de conta, data, valor e texto. Descrição que o banco muda de um mês
para outro gera linha nova (a conciliação por valor e data ajuda). Descrição que
começa com `-` ou `+` aparece com apóstrofo no título.

## Detalhes de implementação

- **Web.** O seletor OFX/CSV fica no formulário de envio. Colunas aparecem de 1 a
  50 e a conversão para o índice 0 da API mora só em `mapeamentoCsv.ts`. "Enviar
  extrato" salva o mapeamento novo ou alterado antes de enviar, para o servidor
  ler como a prévia mostrou. A prévia espera 600 ms, cancela a chamada anterior e
  gasta uma das 10 requisições por minuto do IP (auditoria B4). Texto de célula
  vai sempre como texto do React.
- **Erros.** 404 das rotas de CSV tem erro próprio ("conta ou mapeamento").
  Mensagens saem do status, nunca do corpo.
- **Não coberto no web.** 401 no fluxo CSV, corrida real entre prévias, teclado e
  foco, e o visual da tabela no navegador.
- **Auditoria (security).** 0 crítico, alto e médio; 4 baixos no backlog.
