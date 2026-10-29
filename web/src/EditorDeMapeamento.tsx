import type { ChangeEvent } from 'react'
import { DECIMAIS_DE_CSV, FORMATOS_DE_DATA_DE_CSV, SEPARADORES_DE_CSV, SINAIS_DE_CSV } from './api'
import type { MapeamentoCsv } from './api'
import type { CamposDoMapeamento } from './mapeamentoCsv'
import { TAMANHO_MAXIMO_DO_NOME, TETO_DE_COLUNAS } from './mapeamentoCsv'

const ROTULO_SEPARADOR: Record<MapeamentoCsv['separador'], string> = {
  ponto_e_virgula: 'Ponto e vírgula ( ; )',
  virgula: 'Vírgula ( , )',
  tab: 'Tabulação (tab)',
  barra_vertical: 'Barra vertical ( | )',
}

const ROTULO_DATA: Record<MapeamentoCsv['formatoData'], string> = {
  'dd/MM/yyyy': 'dia/mês/ano, como 31/12/2026',
  'yyyy-MM-dd': 'ano-mês-dia, como 2026-12-31',
  'MM/dd/yyyy': 'mês/dia/ano, como 12/31/2026',
  'dd-MM-yyyy': 'dia-mês-ano, como 31-12-2026',
}

const ROTULO_DECIMAL: Record<MapeamentoCsv['decimal'], string> = {
  virgula: 'Vírgula, como 1.234,56',
  ponto: 'Ponto, como 1,234.56',
}

const ROTULO_SINAL: Record<MapeamentoCsv['sinal'], string> = {
  negativo_despesa: 'A despesa vem negativa',
  positivo_despesa: 'A despesa vem positiva, comum em fatura de cartão',
}

// Escolhe o valor dentro da lista que o select ofereceu, sem cast: o que não está na lista não passa.
function escolher<T extends string>(lista: readonly T[], valor: string): T | null {
  return lista.find((item) => item === valor) ?? null
}

function CampoDeColuna({
  id,
  rotulo,
  valor,
  dica,
  aoMudar,
}: {
  id: string
  rotulo: string
  valor: string
  dica?: string
  aoMudar: (valor: string) => void
}) {
  return (
    <div className="campo">
      <label htmlFor={id}>{rotulo}</label>
      <input
        id={id}
        type="number"
        inputMode="numeric"
        min={1}
        max={TETO_DE_COLUNAS}
        step={1}
        value={valor}
        onChange={(evento) => aoMudar(evento.target.value)}
      />
      {dica !== undefined && <small>{dica}</small>}
    </div>
  )
}

// Campos do mapeamento. O estado fica no pai, porque a prévia e o envio dependem dele.
export default function EditorDeMapeamento({
  campos,
  aoMudar,
}: {
  campos: CamposDoMapeamento
  aoMudar: (campos: CamposDoMapeamento) => void
}) {
  function trocar(sobra: Partial<CamposDoMapeamento>): void {
    aoMudar({ ...campos, ...sobra })
  }

  function aoEscolher<T extends string>(lista: readonly T[], aplicar: (valor: T) => Partial<CamposDoMapeamento>) {
    return (evento: ChangeEvent<HTMLSelectElement>): void => {
      const valor = escolher(lista, evento.target.value)
      if (valor !== null) trocar(aplicar(valor))
    }
  }

  return (
    <>
      <div className="campo">
        <label htmlFor="csv-nome">Nome do mapeamento (o banco, por exemplo)</label>
        <input
          id="csv-nome"
          type="text"
          maxLength={TAMANHO_MAXIMO_DO_NOME}
          value={campos.nome}
          onChange={(evento) => trocar({ nome: evento.target.value })}
        />
      </div>
      <div className="campo">
        <label htmlFor="csv-separador">Separador das colunas</label>
        <select
          id="csv-separador"
          value={campos.separador}
          onChange={aoEscolher(SEPARADORES_DE_CSV, (separador) => ({ separador }))}
        >
          {SEPARADORES_DE_CSV.map((valor) => (
            <option key={valor} value={valor}>
              {ROTULO_SEPARADOR[valor]}
            </option>
          ))}
        </select>
      </div>
      <div className="campo">
        <label htmlFor="csv-formato-data">Formato da data</label>
        <select
          id="csv-formato-data"
          value={campos.formatoData}
          onChange={aoEscolher(FORMATOS_DE_DATA_DE_CSV, (formatoData) => ({ formatoData }))}
        >
          {FORMATOS_DE_DATA_DE_CSV.map((valor) => (
            <option key={valor} value={valor}>
              {ROTULO_DATA[valor]}
            </option>
          ))}
        </select>
      </div>
      <div className="campo">
        <label htmlFor="csv-decimal">Separador dos centavos</label>
        <select
          id="csv-decimal"
          value={campos.decimal}
          onChange={aoEscolher(DECIMAIS_DE_CSV, (decimal) => ({ decimal }))}
        >
          {DECIMAIS_DE_CSV.map((valor) => (
            <option key={valor} value={valor}>
              {ROTULO_DECIMAL[valor]}
            </option>
          ))}
        </select>
      </div>
      <div className="campo">
        <label htmlFor="csv-sinal">Sinal do valor</label>
        <select id="csv-sinal" value={campos.sinal} onChange={aoEscolher(SINAIS_DE_CSV, (sinal) => ({ sinal }))}>
          {SINAIS_DE_CSV.map((valor) => (
            <option key={valor} value={valor}>
              {ROTULO_SINAL[valor]}
            </option>
          ))}
        </select>
      </div>
      <div className="campo">
        <label>
          <input
            type="checkbox"
            checked={campos.temCabecalho}
            onChange={(evento) => trocar({ temCabecalho: evento.target.checked })}
          />{' '}
          A primeira linha é o cabeçalho
        </label>
      </div>
      <fieldset>
        <legend>Colunas do arquivo</legend>
        <p className="item-meta">Conte da esquerda para a direita, começando em 1.</p>
        <CampoDeColuna
          id="csv-coluna-data"
          rotulo="Coluna da data"
          valor={campos.colunaData}
          aoMudar={(colunaData) => trocar({ colunaData })}
        />
        <CampoDeColuna
          id="csv-coluna-valor"
          rotulo="Coluna do valor"
          valor={campos.colunaValor}
          aoMudar={(colunaValor) => trocar({ colunaValor })}
        />
        <CampoDeColuna
          id="csv-coluna-descricao"
          rotulo="Coluna da descrição"
          valor={campos.colunaDescricao}
          aoMudar={(colunaDescricao) => trocar({ colunaDescricao })}
        />
        <CampoDeColuna
          id="csv-coluna-id"
          rotulo="Coluna do identificador (opcional)"
          valor={campos.colunaId}
          dica="Alguns bancos trazem um código único por transação. Ele evita importar a mesma linha duas vezes."
          aoMudar={(colunaId) => trocar({ colunaId })}
        />
      </fieldset>
    </>
  )
}
