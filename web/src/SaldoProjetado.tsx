import { useEffect, useState } from 'react'
import {
  ContaNaoEncontradaError,
  ProjecaoInvalidaError,
  ServicoIndisponivelError,
  SessaoExpiradaError,
  fetchSaldoProjetado,
} from './api'
import type { Conta, DiaProjetado, SaldoProjetado as Projecao } from './api'
import { formatarDiaMes } from './datas'
import { formatarCentavos } from './dinheiro'

type EstadoProjecao =
  | { tipo: 'carregando' }
  | { tipo: 'erro'; mensagem: string }
  | { tipo: 'ok'; projecao: Projecao }

type Periodo = 30 | 90 | 180

const PERIODOS: Periodo[] = [30, 90, 180]
const PERIODO_PADRAO: Periodo = 90

const MENSAGEM_INDISPONIVEL = 'Serviço indisponível. Tente de novo em instantes.'

function mensagemDeLeitura(erro: unknown): string {
  if (erro instanceof ServicoIndisponivelError) return MENSAGEM_INDISPONIVEL
  if (erro instanceof ContaNaoEncontradaError) return 'Essa conta não existe mais.'
  if (erro instanceof ProjecaoInvalidaError && erro.mensagemServidor !== null) {
    return erro.mensagemServidor
  }
  return 'Não foi possível carregar o saldo projetado.'
}

const LARGURA = 320
const ALTURA = 160
const MARGEM_LATERAL = 8
const TOPO = 12
const BASE = 136

type Ponto = { x: number; y: number }

// Number só para desenhar: a diferença sai exata em BigInt e vira pixel no fim.
// Nenhum valor exibido passa por aqui.
function fracaoDoIntervalo(valor: number, minimo: number, maximo: number): number {
  const amplitude = BigInt(maximo) - BigInt(minimo)
  if (amplitude === 0n) return 0.5
  return Number(BigInt(valor) - BigInt(minimo)) / Number(amplitude)
}

function coordenadaY(fracao: number): number {
  return BASE - fracao * (BASE - TOPO)
}

function calcularPontos(dias: DiaProjetado[], minimo: number, maximo: number): Ponto[] {
  const passo = dias.length > 1 ? (LARGURA - 2 * MARGEM_LATERAL) / (dias.length - 1) : 0
  // Um dia só fica no meio; sem isso o ponto cairia na borda esquerda.
  const inicio = dias.length > 1 ? MARGEM_LATERAL : LARGURA / 2
  return dias.map((dia, indice) => ({
    x: inicio + indice * passo,
    y: coordenadaY(fracaoDoIntervalo(dia.saldoCentavos, minimo, maximo)),
  }))
}

function Grafico({ projecao, descricao }: { projecao: Projecao; descricao: string }) {
  const { dias, menorSaldo } = projecao
  const saldos = dias.map((dia) => dia.saldoCentavos)
  const minimo = Math.min(...saldos)
  const maximo = Math.max(...saldos)
  const pontos = calcularPontos(dias, minimo, maximo)
  const indiceDoMenor = dias.findIndex((dia) => dia.data === menorSaldo.data)
  const destaque = indiceDoMenor === -1 ? null : pontos[indiceDoMenor]
  const cruzaZero = minimo < 0 && maximo > 0
  const yDoZero = coordenadaY(fracaoDoIntervalo(0, minimo, maximo))

  return (
    <svg
      className="grafico"
      viewBox={`0 0 ${LARGURA} ${ALTURA}`}
      width="100%"
      role="img"
      aria-label={descricao}
    >
      {cruzaZero && (
        <line
          data-zero
          className="grafico-zero"
          x1={MARGEM_LATERAL}
          x2={LARGURA - MARGEM_LATERAL}
          y1={yDoZero}
          y2={yDoZero}
        />
      )}
      <polyline
        className="grafico-linha"
        fill="none"
        points={pontos.map((ponto) => `${ponto.x.toFixed(1)},${ponto.y.toFixed(1)}`).join(' ')}
      />
      {destaque !== null && (
        <circle
          className={menorSaldo.saldoCentavos < 0 ? 'grafico-menor negativo' : 'grafico-menor'}
          cx={destaque.x}
          cy={destaque.y}
          r={4}
        />
      )}
      <text className="grafico-rotulo" x={MARGEM_LATERAL} y={ALTURA - 6} textAnchor="start">
        {formatarDiaMes(dias[0].data)}
      </text>
      <text className="grafico-rotulo" x={LARGURA - MARGEM_LATERAL} y={ALTURA - 6} textAnchor="end">
        {formatarDiaMes(dias[dias.length - 1].data)}
      </text>
    </svg>
  )
}

function comSinal(centavos: number): string {
  return centavos > 0 ? `+${formatarCentavos(centavos)}` : formatarCentavos(centavos)
}

function Resultado({ projecao }: { projecao: Projecao }) {
  const { dias, menorSaldo } = projecao
  const primeiro = dias[0]
  const ultimo = dias[dias.length - 1]
  const primeiroNegativo = dias.find((dia) => dia.saldoCentavos < 0)
  const comMovimento = dias.filter((dia) => dia.entradasCentavos !== 0 || dia.saidasCentavos !== 0)
  const descricao =
    `Saldo projetado de ${formatarDiaMes(primeiro.data)} a ${formatarDiaMes(ultimo.data)}: ` +
    // Sem a expressão "saldo inicial": ela é o rótulo do campo de nova conta e confunde a busca por rótulo.
    `começa com ${formatarCentavos(primeiro.saldoCentavos)}, ` +
    `termina com ${formatarCentavos(ultimo.saldoCentavos)}, ` +
    `menor saldo ${formatarCentavos(menorSaldo.saldoCentavos)} em ${formatarDiaMes(menorSaldo.data)}`

  return (
    <>
      <p>Saldo atual: {formatarCentavos(projecao.saldoAtualCentavos)}</p>
      <p>
        Saldo em {formatarDiaMes(ultimo.data)}: {formatarCentavos(ultimo.saldoCentavos)}
      </p>
      <p>
        Menor saldo: {formatarCentavos(menorSaldo.saldoCentavos)} em {formatarDiaMes(menorSaldo.data)}
      </p>
      {primeiroNegativo !== undefined && (
        <p className="negativo">Fica negativo em {formatarDiaMes(primeiroNegativo.data)}</p>
      )}
      {projecao.vencidasCentavos !== 0 && (
        <p>Inclui {comSinal(projecao.vencidasCentavos)} de previstas vencidas</p>
      )}
      <Grafico projecao={projecao} descricao={descricao} />
      <h3>Dias com movimento</h3>
      {comMovimento.length === 0 && <p>Nenhum dia com movimento no período.</p>}
      {comMovimento.length > 0 && (
        <ul className="dias-movimento" aria-label="Dias com movimento">
          {comMovimento.map((dia) => (
            <li key={dia.data}>
              <strong>{formatarDiaMes(dia.data)}</strong>
              <span>Entradas: {formatarCentavos(dia.entradasCentavos)}</span>
              <span>Saídas: {formatarCentavos(dia.saidasCentavos)}</span>
              <span>Saldo: {formatarCentavos(dia.saldoCentavos)}</span>
            </li>
          ))}
        </ul>
      )}
    </>
  )
}

export default function SaldoProjetado({
  aoSessaoExpirar,
  contas,
  versaoDeSaldo,
}: {
  aoSessaoExpirar: () => void
  // Contas ativas vindas da tela de Contas; null enquanto ela não carregou.
  contas: Conta[] | null
  // Sobe quando um lançamento muda; a projeção busca de novo para refletir o saldo.
  versaoDeSaldo: number
}) {
  const [periodo, setPeriodo] = useState<Periodo>(PERIODO_PADRAO)
  const [contaEscolhida, setContaEscolhida] = useState('')
  const [estado, setEstado] = useState<EstadoProjecao>({ tipo: 'carregando' })
  const [tentativa, setTentativa] = useState(0)

  // Conta arquivada some da lista de ativas e a escolha volta para todas, sem efeito.
  const contaId = contas?.some((conta) => conta.id === contaEscolhida) ? contaEscolhida : ''
  // Só os ids contam: o saldo muda a cada lançamento e já chega por versaoDeSaldo.
  const idsDasContas = contas?.map((conta) => conta.id).join(',') ?? ''

  useEffect(() => {
    // Descarta a resposta de um período ou conta que o usuário já trocou.
    let ativo = true
    fetchSaldoProjetado(periodo, contaId === '' ? undefined : contaId).then(
      (projecao) => {
        if (ativo) setEstado({ tipo: 'ok', projecao })
      },
      (erro: unknown) => {
        if (!ativo) return
        if (erro instanceof SessaoExpiradaError) {
          aoSessaoExpirar()
          return
        }
        setEstado({ tipo: 'erro', mensagem: mensagemDeLeitura(erro) })
      },
    )
    return () => {
      ativo = false
    }
  }, [periodo, contaId, idsDasContas, tentativa, versaoDeSaldo, aoSessaoExpirar])

  function escolherPeriodo(novo: Periodo): void {
    if (novo === periodo) return
    setEstado({ tipo: 'carregando' })
    setPeriodo(novo)
  }

  function escolherConta(nova: string): void {
    setEstado({ tipo: 'carregando' })
    setContaEscolhida(nova)
  }

  function recarregar(): void {
    setEstado({ tipo: 'carregando' })
    setTentativa((n) => n + 1)
  }

  return (
    <section aria-labelledby="titulo-saldo-projetado">
      <h2 id="titulo-saldo-projetado">Saldo projetado</h2>
      <div role="group" aria-label="Período" className="periodos">
        {PERIODOS.map((opcao) => (
          <button
            key={opcao}
            type="button"
            aria-pressed={opcao === periodo}
            onClick={() => escolherPeriodo(opcao)}
          >
            {opcao} dias
          </button>
        ))}
      </div>
      <div className="campo">
        <label htmlFor="projecao-conta">Conta</label>
        <select
          id="projecao-conta"
          value={contaId}
          onChange={(evento) => escolherConta(evento.target.value)}
        >
          <option value="">Todas as contas ativas</option>
          {(contas ?? []).map((conta) => (
            <option key={conta.id} value={conta.id}>
              {conta.nome}
            </option>
          ))}
        </select>
      </div>
      {estado.tipo === 'carregando' && <p>Carregando saldo projetado...</p>}
      {estado.tipo === 'erro' && (
        <>
          <p role="alert" className="erro">
            {estado.mensagem}
          </p>
          <button type="button" onClick={recarregar}>
            Tentar de novo
          </button>
        </>
      )}
      {estado.tipo === 'ok' && <Resultado projecao={estado.projecao} />}
    </section>
  )
}
