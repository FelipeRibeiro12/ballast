import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import {
  ServicoIndisponivelError,
  SessaoExpiradaError,
  TransferenciaInvalidaError,
  fetchFaturas,
  postTransferencia,
} from './api'
import type { Conta, Fatura } from './api'
import { formatarDataCompleta, intervaloDoMes, mesDeHoje, somarMeses } from './datas'
import { formatarCentavos, lerValorPositivo } from './dinheiro'

const MENSAGEM_INDISPONIVEL = 'Serviço indisponível. Tente de novo em instantes.'
const MENSAGEM_VALOR_INVALIDO =
  'Valor inválido. Use o formato 1.234,56, maior que zero e até 90.071.992.547.409,91.'

const ROTULO_DA_SITUACAO: Record<Fatura['situacao'], string> = {
  aberta: 'Aberta',
  fechada: 'Fechada',
  paga: 'Paga',
}

type EstadoFaturas =
  | { tipo: 'carregando' }
  | { tipo: 'erro'; mensagem: string }
  | { tipo: 'ok'; faturas: Fatura[] }

function mensagemDeLeitura(erro: unknown): string {
  if (erro instanceof ServicoIndisponivelError) return MENSAGEM_INDISPONIVEL
  return 'Não foi possível carregar as faturas.'
}

function mensagemDePagamento(erro: unknown): string {
  if (erro instanceof TransferenciaInvalidaError && erro.mensagemServidor !== null) {
    return erro.mensagemServidor
  }
  if (erro instanceof ServicoIndisponivelError) return MENSAGEM_INDISPONIVEL
  return 'Não foi possível pagar a fatura. Tente de novo.'
}

function eContaDePagamento(conta: Conta): boolean {
  return conta.tipo === 'corrente' || conta.tipo === 'poupanca'
}

function FormularioDePagamento({
  fatura,
  cartao,
  contasAtivas,
  aoPagar,
  aoCancelar,
  aoSessaoExpirar,
}: {
  fatura: Fatura
  cartao: Conta
  contasAtivas: Conta[]
  aoPagar: () => void
  aoCancelar: () => void
  aoSessaoExpirar: () => void
}) {
  const pagadoras = contasAtivas.filter(eContaDePagamento)
  const contaPagamentoId = cartao.cartao?.contaPagamentoId ?? ''
  // Conta de pagamento arquivada não aparece nas ativas: o usuário escolhe outra.
  const pagamentoArquivada = !pagadoras.some((c) => c.id === contaPagamentoId)
  const [origemEscolhida, setOrigemEscolhida] = useState(pagamentoArquivada ? '' : contaPagamentoId)
  const [destinoEscolhido, setDestinoEscolhido] = useState(cartao.id)
  const [titulo, setTitulo] = useState('Pagamento da fatura')
  const [valor, setValor] = useState(formatarCentavos(fatura.restanteCentavos).replace(/[^\d.,]/g, ''))
  const [data, setData] = useState(fatura.vencimento)
  const [enviando, setEnviando] = useState(false)
  const [mensagem, setMensagem] = useState<string | null>(null)
  // O state só atualiza no próximo render; o ref barra o segundo submit síncrono.
  const emVoo = useRef(false)

  const origemId = pagadoras.some((c) => c.id === origemEscolhida) ? origemEscolhida : (pagadoras[0]?.id ?? '')
  const destinoId = contasAtivas.some((c) => c.id === destinoEscolhido) ? destinoEscolhido : cartao.id

  async function enviar(evento: FormEvent<HTMLFormElement>): Promise<void> {
    evento.preventDefault()
    if (emVoo.current) return
    if (origemId === '') {
      setMensagem('Crie ou desarquive uma conta corrente ou poupança para pagar a fatura.')
      return
    }
    if (origemId === destinoId) {
      setMensagem('Escolha contas diferentes para origem e destino.')
      return
    }
    const valorCentavos = lerValorPositivo(valor)
    if (valorCentavos === null) {
      setMensagem(MENSAGEM_VALOR_INVALIDO)
      return
    }
    emVoo.current = true
    setEnviando(true)
    setMensagem(null)
    try {
      await postTransferencia({
        contaOrigemId: origemId,
        contaDestinoId: destinoId,
        valorCentavos,
        data,
        ...(titulo.trim() === '' ? {} : { titulo: titulo.trim() }),
      })
      aoPagar()
    } catch (erro) {
      if (erro instanceof SessaoExpiradaError) {
        aoSessaoExpirar()
        return
      }
      setMensagem(mensagemDePagamento(erro))
    } finally {
      emVoo.current = false
      setEnviando(false)
    }
  }

  const id = `pagamento-${fatura.vencimento}`
  const descricaoDoErro = mensagem === null ? undefined : `${id}-erro`

  return (
    <form onSubmit={(evento) => void enviar(evento)} aria-label="Pagamento da fatura">
      <h4>Pagar fatura de {formatarDataCompleta(fatura.vencimento)}</h4>
      {pagamentoArquivada && (
        <p role="status">A conta de pagamento deste cartão está arquivada. Escolha outra conta.</p>
      )}
      <p>
        <small>Pagamento feito depois do próximo fechamento conta na fatura seguinte.</small>
      </p>
      <div className="campo">
        <label htmlFor={`${id}-origem`}>Conta de origem</label>
        <select
          id={`${id}-origem`}
          value={origemId}
          required
          onChange={(evento) => setOrigemEscolhida(evento.target.value)}
        >
          {pagadoras.map((conta) => (
            <option key={conta.id} value={conta.id}>
              {conta.nome}
            </option>
          ))}
        </select>
      </div>
      <div className="campo">
        <label htmlFor={`${id}-destino`}>Conta de destino</label>
        <select
          id={`${id}-destino`}
          value={destinoId}
          required
          onChange={(evento) => setDestinoEscolhido(evento.target.value)}
        >
          {contasAtivas.map((conta) => (
            <option key={conta.id} value={conta.id}>
              {conta.nome}
            </option>
          ))}
        </select>
      </div>
      <div className="campo">
        <label htmlFor={`${id}-valor`}>Valor em reais</label>
        <input
          id={`${id}-valor`}
          type="text"
          inputMode="decimal"
          autoComplete="off"
          required
          value={valor}
          aria-describedby={descricaoDoErro}
          onChange={(evento) => setValor(evento.target.value)}
        />
      </div>
      <div className="campo">
        <label htmlFor={`${id}-data`}>Data do pagamento</label>
        <input
          id={`${id}-data`}
          type="date"
          required
          min="1900-01-01"
          max="2199-12-31"
          value={data}
          onChange={(evento) => setData(evento.target.value)}
        />
      </div>
      <div className="campo">
        <label htmlFor={`${id}-titulo`}>Título</label>
        <input
          id={`${id}-titulo`}
          type="text"
          maxLength={100}
          value={titulo}
          onChange={(evento) => setTitulo(evento.target.value)}
        />
      </div>
      {mensagem !== null && (
        <p id={`${id}-erro`} role="alert" className="erro">
          {mensagem}
        </p>
      )}
      <button type="submit" disabled={enviando}>
        {enviando ? 'Pagando...' : 'Confirmar pagamento'}
      </button>
      <button type="button" onClick={aoCancelar}>
        Cancelar
      </button>
    </form>
  )
}

export default function Faturas({
  cartao,
  contasAtivas,
  aoMudarSaldo,
  aoSessaoExpirar,
}: {
  cartao: Conta
  contasAtivas: Conta[]
  // Avisa o app que o saldo mudou, para as contas e os lançamentos buscarem de novo.
  aoMudarSaldo: () => void
  aoSessaoExpirar: () => void
}) {
  const [estado, setEstado] = useState<EstadoFaturas>({ tipo: 'carregando' })
  const [tentativa, setTentativa] = useState(0)
  const [pagando, setPagando] = useState<Fatura | null>(null)

  useEffect(() => {
    // De 3 meses atrás a 6 à frente, por vencimento, em datas locais.
    const hoje = mesDeHoje()
    const de = intervaloDoMes(somarMeses(hoje, -3)).de
    const ate = intervaloDoMes(somarMeses(hoje, 6)).ate
    let ativo = true
    fetchFaturas(cartao.id, de, ate).then(
      (resposta) => {
        if (ativo) setEstado({ tipo: 'ok', faturas: resposta.faturas })
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
  }, [cartao.id, tentativa, aoSessaoExpirar])

  function recarregar(): void {
    setEstado({ tipo: 'carregando' })
    setTentativa((n) => n + 1)
  }

  return (
    <div className="faturas">
      <h3>Faturas de {cartao.nome}</h3>
      {estado.tipo === 'carregando' && <p>Carregando faturas...</p>}
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
      {estado.tipo === 'ok' && estado.faturas.length === 0 && <p>Nenhuma fatura no período.</p>}
      {estado.tipo === 'ok' && estado.faturas.length > 0 && (
        <ul className="contas">
          {estado.faturas.map((fatura) => (
            <li key={fatura.vencimento}>
              <div className="conta-dados">
                <strong>Vence em {formatarDataCompleta(fatura.vencimento)}</strong>
                <span>Fecha em {formatarDataCompleta(fatura.fechamento)}</span>
                <span>Total: {formatarCentavos(fatura.totalCentavos)}</span>
                <span>Pago: {formatarCentavos(fatura.pagoCentavos)}</span>
                <span>Restante: {formatarCentavos(fatura.restanteCentavos)}</span>
                <span>Situação: {ROTULO_DA_SITUACAO[fatura.situacao]}</span>
              </div>
              {fatura.situacao === 'fechada' && fatura.restanteCentavos > 0 && (
                <button
                  type="button"
                  aria-label={`Pagar fatura de ${formatarDataCompleta(fatura.vencimento)}`}
                  onClick={() => setPagando(fatura)}
                >
                  Pagar fatura
                </button>
              )}
            </li>
          ))}
        </ul>
      )}
      {pagando !== null && (
        <FormularioDePagamento
          key={pagando.vencimento}
          fatura={pagando}
          cartao={cartao}
          contasAtivas={contasAtivas}
          aoPagar={() => {
            setPagando(null)
            recarregar()
            aoMudarSaldo()
          }}
          aoCancelar={() => setPagando(null)}
          aoSessaoExpirar={aoSessaoExpirar}
        />
      )}
    </div>
  )
}
