import { useEffect, useState } from 'react'
import { fetchRelatorio } from './api'
import type { DespesaDaCategoria, ReceitaEDespesaDoMes, RelatorioDoMes } from './api'
import { lerMesIso, mesDeHoje, mesIso, rotuloDoMes, somarMeses } from './datas'
import type { Mes } from './datas'
import { formatarCentavos } from './dinheiro'
import { formatarCompacto, fracaoDoMaximo, percentualDoTotal, rotuloCurtoDoMes, topoDoEixo } from './graficos'
import { IconeSetaDireita, IconeSetaEsquerda } from './icones'
import { carregar } from './leitura'
import type { Estado } from './leitura'
import AtalhosDePlanejamento from './Planejamento'
import { Carregando, EstadoVazio } from './ui'

// A faixa de mês que a API aceita.
const ANO_MINIMO = 1900
const ANO_MAXIMO = 2199

function parteDoTotal(gastoCentavos: number, totalCentavos: number): string {
  const percentual = percentualDoTotal(gastoCentavos, totalCentavos)
  return percentual === 0 && gastoCentavos > 0 ? '<1%' : `${percentual}%`
}

function resultado(mes: ReceitaEDespesaDoMes): string {
  return formatarCentavos(BigInt(mes.receitaCentavos) - BigInt(mes.despesaCentavos))
}

// Uma série só: a barra tem a cor da marca e o texto fica na cor do texto. A maior categoria
// ocupa a largura toda, para as pequenas ainda aparecerem.
function DespesasPorCategoria({ relatorio }: { relatorio: RelatorioDoMes }) {
  const categorias: DespesaDaCategoria[] = relatorio.despesasPorCategoria
  const maior = categorias[0]?.gastoCentavos ?? 0
  return (
    <section className="cartao" aria-labelledby="titulo-por-categoria">
      <h3 id="titulo-por-categoria" className="titulo-cartao">
        Despesas por categoria
      </h3>
      {categorias.length === 0 ? (
        <EstadoVazio
          titulo="Nenhuma despesa neste mês."
          dica="Conta o que já aconteceu, pela data da compra: a do cartão entra no mês em que você comprou."
        />
      ) : (
        <>
          <p className="relatorio-total">
            Total <strong>{formatarCentavos(relatorio.totalDespesasCentavos)}</strong>
          </p>
          <ol className="relatorio-categorias" aria-label="Despesas por categoria">
            {categorias.map((categoria) => (
              <li key={categoria.categoriaId}>
                <div className="relatorio-categoria-linha">
                  <span className="relatorio-categoria-nome">{categoria.categoriaNome}</span>
                  <span className="relatorio-categoria-valor">{formatarCentavos(categoria.gastoCentavos)}</span>
                  <small className="relatorio-categoria-parte">
                    {parteDoTotal(categoria.gastoCentavos, relatorio.totalDespesasCentavos)}
                  </small>
                </div>
                <div className="relatorio-trilho" aria-hidden="true">
                  <div style={{ width: `${fracaoDoMaximo(categoria.gastoCentavos, maior)}%` }} />
                </div>
              </li>
            ))}
          </ol>
        </>
      )}
    </section>
  )
}

// Colunas duplas por mês, num eixo só. Tocar numa coluna escolhe o mês do detalhe; a tabela
// tem todos os valores para quem não lê o desenho.
function ReceitasEDespesas({ meses }: { meses: ReceitaEDespesaDoMes[] }) {
  const [escolhido, setEscolhido] = useState(meses.length - 1)
  const maior = meses.reduce((max, mes) => Math.max(max, mes.receitaCentavos, mes.despesaCentavos), 0)
  const topo = topoDoEixo(maior)
  const detalhe = meses[escolhido] ?? meses[meses.length - 1]

  return (
    <section className="cartao" aria-labelledby="titulo-receitas-despesas">
      <h3 id="titulo-receitas-despesas" className="titulo-cartao">
        Receitas e despesas
      </h3>
      <p className="relatorio-legenda">
        <span className="relatorio-chave relatorio-chave-receita" aria-hidden="true" />
        Receitas
        <span className="relatorio-chave relatorio-chave-despesa" aria-hidden="true" />
        Despesas
      </p>
      <div className="relatorio-colunas">
        <div className="relatorio-plot">
          <div className="relatorio-guias" aria-hidden="true">
            <span style={{ bottom: '100%' }} />
            <span style={{ bottom: '50%' }} />
            <span style={{ bottom: '0%' }} />
          </div>
          <div className="relatorio-meses" role="group" aria-label="Receitas e despesas por mês">
            {meses.map((mes, indice) => {
              const alvo = lerMesIso(mes.mes)
              return (
                <button
                  key={mes.mes}
                  type="button"
                  className="relatorio-mes"
                  aria-pressed={indice === escolhido}
                  aria-label={`${rotuloDoMes(alvo)}: receitas ${formatarCentavos(mes.receitaCentavos)}, despesas ${formatarCentavos(mes.despesaCentavos)}`}
                  onClick={() => setEscolhido(indice)}
                >
                  <span className="relatorio-barras" aria-hidden="true">
                    <span
                      className={mes.receitaCentavos > 0 ? 'relatorio-receita' : 'relatorio-receita relatorio-zero'}
                      style={{ height: `${fracaoDoMaximo(mes.receitaCentavos, topo)}%` }}
                    />
                    <span
                      className={mes.despesaCentavos > 0 ? 'relatorio-despesa' : 'relatorio-despesa relatorio-zero'}
                      style={{ height: `${fracaoDoMaximo(mes.despesaCentavos, topo)}%` }}
                    />
                  </span>
                  <span className="relatorio-rotulo" aria-hidden="true">
                    {rotuloCurtoDoMes(alvo)}
                  </span>
                </button>
              )
            })}
          </div>
        </div>
        <div className="relatorio-eixo" aria-hidden="true">
          <span style={{ bottom: '100%' }}>{formatarCompacto(topo)}</span>
          <span style={{ bottom: '50%' }}>{formatarCompacto(Math.floor(topo / 2))}</span>
          <span style={{ bottom: '0%' }}>{formatarCompacto(0)}</span>
        </div>
      </div>
      {detalhe !== undefined && (
        <div role="status" aria-label="Mês escolhido" className="relatorio-detalhe">
          <strong>{rotuloDoMes(lerMesIso(detalhe.mes))}</strong>
          <span>Receitas {formatarCentavos(detalhe.receitaCentavos)}</span>
          <span>Despesas {formatarCentavos(detalhe.despesaCentavos)}</span>
          <span>Resultado {resultado(detalhe)}</span>
        </div>
      )}
      <details className="relatorio-tabela">
        <summary>Ver em tabela</summary>
        <table aria-label="Receitas e despesas por mês">
          <thead>
            <tr>
              <th scope="col">Mês</th>
              <th scope="col">Receitas</th>
              <th scope="col">Despesas</th>
              <th scope="col">Resultado</th>
            </tr>
          </thead>
          <tbody>
            {meses.map((mes) => (
              <tr key={mes.mes}>
                <th scope="row">{rotuloDoMes(lerMesIso(mes.mes))}</th>
                <td>{formatarCentavos(mes.receitaCentavos)}</td>
                <td>{formatarCentavos(mes.despesaCentavos)}</td>
                <td>{resultado(mes)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </details>
    </section>
  )
}

// Por competência e só o que já aconteceu (ADR-046). O consumo do orçamento continua na tela de
// Orçamento, que já desenha a barra de cada teto.
export default function Relatorios({ aoSessaoExpirar }: { aoSessaoExpirar: () => void }) {
  const [mes, setMes] = useState<Mes>(() => mesDeHoje())
  const [estado, setEstado] = useState<Estado<RelatorioDoMes>>({ tipo: 'carregando' })

  useEffect(() => {
    let vale = true
    carregar(() => fetchRelatorio(mesIso(mes)), 'o relatório', aoSessaoExpirar, setEstado, () => vale)
    return () => {
      vale = false
    }
  }, [mes, aoSessaoExpirar])

  function navegar(delta: number): void {
    setEstado({ tipo: 'carregando' })
    setMes((atual) => somarMeses(atual, delta))
  }

  return (
    <section aria-labelledby="titulo-relatorios">
      <header className="pagina-topo">
        <h2 id="titulo-relatorios">Relatórios</h2>
      </header>
      <AtalhosDePlanejamento atual="relatorios" />
      <div className="mes-barra">
        <div className="mes-nav">
          <button
            type="button"
            aria-label="Mês anterior"
            disabled={mes.ano <= ANO_MINIMO && mes.mes === 1}
            onClick={() => navegar(-1)}
          >
            <IconeSetaEsquerda />
          </button>
          <strong aria-live="polite">{rotuloDoMes(mes)}</strong>
          <button
            type="button"
            aria-label="Próximo mês"
            disabled={mes.ano >= ANO_MAXIMO && mes.mes === 12}
            onClick={() => navegar(1)}
          >
            <IconeSetaDireita />
          </button>
        </div>
      </div>
      {estado.tipo === 'carregando' && <Carregando texto="Carregando relatório..." />}
      {estado.tipo === 'erro' && (
        <p role="alert" className="erro">
          {estado.mensagem}
        </p>
      )}
      {estado.tipo === 'ok' && (
        <div className="relatorios-grade">
          <DespesasPorCategoria relatorio={estado.dados} />
          {/* A chave reinicia o mês escolhido quando o relatório troca. */}
          <ReceitasEDespesas key={estado.dados.mes} meses={estado.dados.receitasEDespesas} />
        </div>
      )}
    </section>
  )
}
