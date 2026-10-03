# ADR-008: Importação de extrato e fatura

**Status:** aceito
**Data:** 2026-10-03

## Contexto

O usuário quer poder enviar a fatura do cartão ou o extrato da conta em vez de
digitar cada lançamento.

Três formatos possíveis, com dificuldade muito diferente:

| Formato | Dificuldade | Confiabilidade |
|---|---|---|
| OFX | baixa | alta — formato padronizado, tem identificador por transação |
| CSV | média | média — cada banco tem colunas próprias, precisa de mapeamento |
| PDF de fatura | **alta** | **baixa** — layout muda sem aviso e a extração quebra |

## Decisão

- **OFX primeiro** (F22). É o formato padronizado de extrato bancário e traz
  `FITID`, um identificador por transação que resolve deduplicação de graça.
- **CSV depois** (F23), com tela de mapeamento de coluna salvável por banco.
- **PDF de fatura por último** (F26), e marcado como melhor esforço: resultado
  sempre vai para revisão manual, nunca é importado direto.
- **Toda importação passa por uma etapa de revisão.** Nada entra no extrato
  sem o usuário confirmar.

## Justificativa

O problema difícil não é ler o arquivo — é **conciliar**. O usuário já lançou
o almoço de ontem na mão; o extrato traz o mesmo almoço. Importar sem conciliar
duplica tudo e destrói a confiança no saldo, que é a única coisa que o app
precisa acertar.

Daí o desenho em duas etapas: a importação grava as linhas cruas e propõe uma
decisão por linha (importar, ignorar, ou conciliar com um lançamento
existente); o usuário confirma. Só então viram lançamento.

A deduplicação usa, em ordem:

1. `hash_arquivo` — o mesmo arquivo não é importado duas vezes
2. `hash_externo` por linha — `FITID` no OFX, ou hash de (data, valor,
   descrição) nos outros
3. **Casamento aproximado** contra lançamento existente: mesma conta, valor
   igual, data a até 3 dias de distância → proposto como conciliação

O terceiro item é heurística, e é por isso que existe revisão: heurística erra,
e errar sozinho num app de dinheiro é inaceitável.

## Alternativas descartadas

- **Importar direto, sem revisão** — mais rápido, mas qualquer falso positivo
  ou duplicata entra silenciosamente no saldo.
- **Só CSV, por ser mais comum de exportar** — CSV não tem identificador de
  transação, então a deduplicação fica inteiramente heurística.
- **Scraping do internet banking** — quebra, viola termo de uso, e exige
  guardar credencial bancária. Está fora de escopo por decisão de segurança
  (ver `docs/ameacas.md`).
- **Open Finance** — exige ser instituição regulada ou pagar agregador. Fora de
  escopo em `visao.md`.

## Consequência

- `lancamento` ganha `hash_externo` com unicidade por usuário, e vínculo com a
  linha de importação de onde veio.
- Arquivo enviado é entrada não confiável: limite de tamanho, limite de linhas,
  e cuidado com fórmula injetada em CSV. Está em `docs/ameacas.md`.
- O arquivo original **não é guardado** depois de processado. Extrato bancário
  em disco é dado sensível sem necessidade; o que fica é a linha crua em
  `jsonb`, que basta para auditar.

## Limitação reconhecida

PDF de fatura vai falhar com alguma frequência, e isso é esperado, não bug. A
interface precisa deixar claro que é melhor esforço e que a revisão é
obrigatória. Se virar fonte de frustração, a resposta certa é remover o
recurso, não insistir em melhorar o parser de cada banco.
