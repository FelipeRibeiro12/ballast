import type { CsvPrevia, CsvPreviaLinha, ParametrosDoCsv } from './api'
import { formatarDataCompleta } from './datas'
import { formatarCentavos } from './dinheiro'

function papelDaColuna(indice: number, parametros: ParametrosDoCsv): string {
  if (indice === parametros.colunaData) return ' (data)'
  if (indice === parametros.colunaValor) return ' (valor)'
  if (indice === parametros.colunaDescricao) return ' (descrição)'
  if (indice === parametros.colunaId) return ' (identificador)'
  return ''
}

function leitura(linha: CsvPreviaLinha): string {
  if (!linha.valida) return `Linha com problema: ${linha.erro ?? 'não consegui ler'}`
  const partes: string[] = []
  if (linha.data !== null) partes.push(formatarDataCompleta(linha.data))
  if (linha.tipo !== null) partes.push(linha.tipo === 'receita' ? 'Receita' : 'Despesa')
  if (linha.valorCentavos !== null) partes.push(formatarCentavos(linha.valorCentavos))
  partes.push(linha.titulo)
  return partes.join(' | ')
}

const COMECA_COM_APOSTROFO = /^'/

// Todo texto entra como texto do React. O apóstrofo que a API põe no começo de fórmula fica à vista de propósito.
export default function PreviaCsv({ previa, parametros }: { previa: CsvPrevia; parametros: ParametrosDoCsv }) {
  const largura = Math.max(previa.colunasDetectadas, ...previa.linhas.map((linha) => linha.celulas.length))
  const colunas = Array.from({ length: largura }, (_, indice) => indice)
  const temApostrofo = previa.linhas.some((linha) => linha.celulas.some((celula) => COMECA_COM_APOSTROFO.test(celula)))

  return (
    <div>
      <p className="item-meta">
        {previa.linhas.length < previa.totalLinhas
          ? `Mostrando as primeiras ${previa.linhas.length} de ${previa.totalLinhas} linhas.`
          : `${previa.totalLinhas} linhas no arquivo.`}
      </p>
      {temApostrofo && (
        <p className="item-meta">
          O apóstrofo no começo de uma célula foi colocado de propósito, para o texto não virar fórmula numa planilha.
        </p>
      )}
      <div className="relatorio-tabela previa-csv">
        <table aria-label="Prévia do arquivo">
          <thead>
            <tr>
              <th scope="col">Linha</th>
              {colunas.map((indice) => (
                <th key={indice} scope="col">
                  {`Coluna ${indice + 1}${papelDaColuna(indice, parametros)}`}
                </th>
              ))}
              <th scope="col">Como o app lê</th>
            </tr>
          </thead>
          <tbody>
            {previa.linhas.map((linha) => (
              <tr key={linha.ordem} className={linha.valida ? undefined : 'erro'}>
                <th scope="row">{linha.ordem}</th>
                {colunas.map((indice) => (
                  <td key={indice}>{linha.celulas[indice] ?? ''}</td>
                ))}
                <td>{leitura(linha)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}
