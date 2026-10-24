import { useEffect, useRef, useState } from 'react'
import type { KeyboardEvent } from 'react'
import { fetchPatrimonioMensal } from './api'
import type { PatrimonioDoMes } from './api'
import { formatarDiaMes, lerMesIso, mesIso, rotuloDoMes, ultimoDiaDoMes } from './datas'
import type { Mes } from './datas'
import { formatarCentavos } from './dinheiro'
import { faixaDoEixo, formatarCompacto, rotuloCurtoDoMes } from './graficos'
import { carregar } from './leitura'
import type { Estado } from './leitura'
import { Carregando, EstadoVazio } from './ui'
import { useLargura } from './useLargura'

const ALTURA = 200
const TOPO = 12
const BASE = 168
const MARGEM_ESQUERDA = 8
// Coluna do eixo à direita, para o rótulo nunca cobrir a linha.
const MARGEM_DIREITA = 56
// Um rótulo de mês a cada três pontos, contando do último: doze cabem em 360px sem encostar.
const PASSO_DO_ROTULO = 3

function comSinal(centavos: bigint): string {
  return centavos > 0n ? `+${formatarCentavos(centavos)}` : formatarCentavos(centavos)
}

function ateQuando(ponto: PatrimonioDoMes): string {
  const alvo = lerMesIso(ponto.mes)
  const fimDoMes = `${ponto.mes}-${String(ultimoDiaDoMes(alvo)).padStart(2, '0')}`
  return ponto.data === fimDoMes ? '' : `, até ${formatarDiaMes(ponto.data)}`
}

// Number só para desenhar: a diferença sai exata em BigInt e vira pixel no fim.
function fracao(valor: number, baixo: number, alto: number): number {
  return Number(BigInt(valor) - BigInt(baixo)) / Number(BigInt(alto) - BigInt(baixo))
}

function Grafico({
  meses,
  escolhido,
  escolher,
}: {
  meses: PatrimonioDoMes[]
  escolhido: number
  escolher: (indice: number) => void
}) {
  const caixa = useRef<HTMLDivElement>(null)
  const largura = useLargura(caixa)
  const totais = meses.map((ponto) => ponto.totalCentavos)
  const { baixo, alto } = faixaDoEixo(Math.min(...totais), Math.max(...totais))
  const y = (valor: number): number => BASE - fracao(valor, baixo, alto) * (BASE - TOPO)
  const direita = largura - MARGEM_DIREITA
  const passo = meses.length > 1 ? (direita - MARGEM_ESQUERDA) / (meses.length - 1) : 0
  const x = (indice: number): number => (meses.length > 1 ? MARGEM_ESQUERDA + indice * passo : direita / 2)
  const pontos = meses.map((ponto, indice) => `${x(indice).toFixed(1)},${y(ponto.totalCentavos).toFixed(1)}`)
  const yDoZero = y(0)
  const area = `M${x(0).toFixed(1)},${yDoZero.toFixed(1)} L${pontos.join(' L')} L${x(meses.length - 1).toFixed(1)},${yDoZero.toFixed(1)} Z`
  const guias = baixo < 0 ? [alto, 0, baixo] : [alto, Math.floor(alto / 2), 0]
  const largo = Math.max(passo, 24)

  function teclar(evento: KeyboardEvent<SVGRectElement>, indice: number): void {
    if (evento.key === 'Enter' || evento.key === ' ') {
      evento.preventDefault()
      escolher(indice)
    }
  }

  return (
    <div ref={caixa} className="grafico-caixa">
      <svg
        className="grafico"
        viewBox={`0 0 ${largura} ${ALTURA}`}
        width="100%"
        height={ALTURA}
        role="group"
        aria-label="Patrimônio por mês"
      >
        <defs>
          <linearGradient id="patrimonio-degrade" x1="0" y1="0" x2="0" y2="1">
            <stop className="grafico-area-topo" offset="0" />
            <stop className="grafico-area-base" offset="1" />
          </linearGradient>
        </defs>
        {guias.map((valor) => (
          <g key={valor} aria-hidden="true">
            <line className="grafico-base" x1={MARGEM_ESQUERDA} x2={direita} y1={y(valor)} y2={y(valor)} />
            <text className="grafico-rotulo" x={direita + 8} y={y(valor) + 4}>
              {`${valor < 0 ? '-' : ''}${formatarCompacto(Math.abs(valor))}`}
            </text>
          </g>
        ))}
        {/* Faixa invisível por mês, maior que o ponto: tocar, passar o mouse ou o teclado escolhe o mês.
            Vem antes da linha para o destaque de foco ficar embaixo do traço. */}
        {meses.map((ponto, indice) => (
          <rect
            key={ponto.mes}
            className="patrimonio-alvo"
            x={x(indice) - largo / 2}
            y={TOPO}
            width={largo}
            height={BASE - TOPO}
            role="button"
            tabIndex={0}
            aria-pressed={indice === escolhido}
            aria-label={`${rotuloDoMes(lerMesIso(ponto.mes))}: ${formatarCentavos(ponto.totalCentavos)}`}
            onClick={() => escolher(indice)}
            onMouseEnter={() => escolher(indice)}
            onKeyDown={(evento) => teclar(evento, indice)}
          />
        ))}
        {meses.length > 1 && <path d={area} fill="url(#patrimonio-degrade)" aria-hidden="true" />}
        <polyline className="grafico-linha" fill="none" points={pontos.join(' ')} aria-hidden="true" />
        <line
          className="patrimonio-mira"
          x1={x(escolhido)}
          x2={x(escolhido)}
          y1={TOPO}
          y2={BASE}
          aria-hidden="true"
        />
        <circle
          className="patrimonio-ponto"
          cx={x(escolhido)}
          cy={y(meses[escolhido].totalCentavos)}
          r={4}
          aria-hidden="true"
        />
        {meses.map((ponto, indice) =>
          (meses.length - 1 - indice) % PASSO_DO_ROTULO === 0 ? (
            <text
              key={ponto.mes}
              className="grafico-rotulo"
              x={x(indice)}
              y={ALTURA - 8}
              textAnchor={x(indice) < 16 ? 'start' : 'middle'}
              aria-hidden="true"
            >
              {rotuloCurtoDoMes(lerMesIso(ponto.mes))}
            </text>
          ) : null,
        )}
      </svg>
    </div>
  )
}

function Serie({ meses }: { meses: PatrimonioDoMes[] }) {
  const [escolhido, setEscolhido] = useState(meses.length - 1)
  const primeiro = meses[0]
  const ultimo = meses[meses.length - 1]
  const detalhe = meses[escolhido]
  const semCotacao = meses.filter((ponto) => ponto.posicoesSemCotacao > 0).length
  const mudanca = BigInt(ultimo.totalCentavos) - BigInt(primeiro.totalCentavos)

  return (
    <>
      <p className="patrimonio-atual">
        <strong>{formatarCentavos(ultimo.totalCentavos)}</strong>
        {meses.length > 1 && (
          <small>
            {comSinal(mudanca)} desde {rotuloDoMes(lerMesIso(primeiro.mes))}
          </small>
        )}
      </p>
      {semCotacao > 0 && (
        <p className="legenda">
          Em {semCotacao === 1 ? '1 mês' : `${semCotacao} meses`} alguma posição de cripto ficou fora por falta de
          cotação.
        </p>
      )}
      <Grafico meses={meses} escolhido={escolhido} escolher={setEscolhido} />
      <div role="status" aria-label="Mês escolhido" className="relatorio-detalhe">
        <strong>
          {rotuloDoMes(lerMesIso(detalhe.mes))}
          {ateQuando(detalhe)}
        </strong>
        <span>Total {formatarCentavos(detalhe.totalCentavos)}</span>
        <span>Contas {formatarCentavos(detalhe.contasCentavos)}</span>
        <span>Cripto {formatarCentavos(detalhe.criptoCentavos)}</span>
        <span>Renda fixa {formatarCentavos(detalhe.rendaFixaCentavos)}</span>
        {detalhe.posicoesSemCotacao > 0 && (
          <span>
            {detalhe.posicoesSemCotacao === 1
              ? '1 posição sem cotação ficou fora do total'
              : `${detalhe.posicoesSemCotacao} posições sem cotação ficaram fora do total`}
          </span>
        )}
      </div>
      <details className="relatorio-tabela">
        <summary>Ver em tabela</summary>
        <table aria-label="Patrimônio por mês">
          <thead>
            <tr>
              <th scope="col">Mês</th>
              <th scope="col">Total</th>
              <th scope="col">Contas</th>
              <th scope="col">Cripto</th>
              <th scope="col">Renda fixa</th>
            </tr>
          </thead>
          <tbody>
            {meses.map((ponto) => (
              <tr key={ponto.mes}>
                <th scope="row">{rotuloDoMes(lerMesIso(ponto.mes))}</th>
                <td>{formatarCentavos(ponto.totalCentavos)}</td>
                <td>{formatarCentavos(ponto.contasCentavos)}</td>
                <td>{formatarCentavos(ponto.criptoCentavos)}</td>
                <td>{formatarCentavos(ponto.rendaFixaCentavos)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </details>
    </>
  )
}

// Patrimônio no fim de cada mês, até hoje (ADR-047). Uma série só: a linha tem a cor da marca e o
// título diz o que ela é, sem legenda.
export default function PatrimonioNoTempo({ mes, aoSessaoExpirar }: { mes: Mes; aoSessaoExpirar: () => void }) {
  const [estado, setEstado] = useState<Estado<PatrimonioDoMes[]>>({ tipo: 'carregando' })

  useEffect(() => {
    let vale = true
    setEstado({ tipo: 'carregando' })
    carregar(
      () => fetchPatrimonioMensal(mesIso(mes)).then((serie) => serie.meses),
      'o patrimônio',
      aoSessaoExpirar,
      setEstado,
      () => vale,
    )
    return () => {
      vale = false
    }
  }, [mes, aoSessaoExpirar])

  return (
    <section className="cartao" aria-labelledby="titulo-patrimonio">
      <h3 id="titulo-patrimonio" className="titulo-cartao">
        Patrimônio
      </h3>
      {estado.tipo === 'carregando' && <Carregando texto="Carregando o patrimônio..." linhas={2} />}
      {estado.tipo === 'erro' && (
        <p role="alert" className="erro">
          {estado.mensagem}
        </p>
      )}
      {estado.tipo === 'ok' && estado.dados.length === 0 && (
        <EstadoVazio titulo="Sem patrimônio para mostrar neste período." dica="O gráfico vai até hoje." />
      )}
      {estado.tipo === 'ok' && estado.dados.length > 0 && (
        // A chave reinicia o mês escolhido quando a série troca.
        <Serie key={estado.dados[estado.dados.length - 1].mes} meses={estado.dados} />
      )}
    </section>
  )
}
