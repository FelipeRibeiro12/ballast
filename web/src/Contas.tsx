import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import {
  ContaInvalidaError,
  ContaNaoEncontradaError,
  NomeDeContaRepetidoError,
  ServicoIndisponivelError,
  SessaoExpiradaError,
  fetchContas,
  patchConta,
  postConta,
} from './api'
import type { Conta } from './api'
import { formatarCentavos, lerCentavos } from './dinheiro'

type EstadoLista =
  | { tipo: 'carregando' }
  | { tipo: 'erro'; mensagem: string }
  | { tipo: 'ok'; contas: Conta[] }

type TipoDeConta = Conta['tipo']

const ROTULO_DO_TIPO: Record<TipoDeConta, string> = {
  corrente: 'Corrente',
  poupanca: 'Poupança',
  dinheiro: 'Dinheiro',
}

const TIPOS: TipoDeConta[] = ['corrente', 'poupanca', 'dinheiro']

// Mesma ordem do servidor: nome sem diferenciar caixa nem acento, id para desempatar.
function compararContas(a: Conta, b: Conta): number {
  const porNome = a.nome.localeCompare(b.nome, 'pt-BR', { sensitivity: 'base' })
  if (porNome !== 0) return porNome
  return a.id < b.id ? -1 : a.id > b.id ? 1 : 0
}

const MENSAGEM_INDISPONIVEL = 'Serviço indisponível. Tente de novo em instantes.'

function mensagemDeLeitura(erro: unknown): string {
  if (erro instanceof ServicoIndisponivelError) return MENSAGEM_INDISPONIVEL
  return 'Não foi possível carregar as contas.'
}

function mensagemDeCriacao(erro: unknown): string {
  if (erro instanceof NomeDeContaRepetidoError) return 'Já existe uma conta com esse nome.'
  if (erro instanceof ContaInvalidaError && erro.mensagemServidor !== null) {
    return erro.mensagemServidor
  }
  if (erro instanceof ServicoIndisponivelError) return MENSAGEM_INDISPONIVEL
  return 'Não foi possível criar a conta. Tente de novo.'
}

function mensagemDeArquivamento(erro: unknown): string {
  if (erro instanceof ContaNaoEncontradaError) return 'Essa conta não existe mais.'
  if (erro instanceof ServicoIndisponivelError) return MENSAGEM_INDISPONIVEL
  return 'Não foi possível alterar a conta. Tente de novo.'
}

function NovaConta({
  aoCriar,
  aoSessaoExpirar,
}: {
  aoCriar: (conta: Conta) => void
  aoSessaoExpirar: () => void
}) {
  const [nome, setNome] = useState('')
  const [tipo, setTipo] = useState<TipoDeConta>('corrente')
  const [saldo, setSaldo] = useState('')
  const [enviando, setEnviando] = useState(false)
  const [mensagem, setMensagem] = useState<string | null>(null)
  // O state só atualiza no próximo render; o ref barra o segundo submit síncrono.
  const emVoo = useRef(false)

  async function enviar(evento: FormEvent<HTMLFormElement>): Promise<void> {
    evento.preventDefault()
    if (emVoo.current) return
    // Campo vazio vale zero; texto fora do formato não chega à API.
    const saldoInicialCentavos = saldo.trim() === '' ? 0 : lerCentavos(saldo)
    if (saldoInicialCentavos === null) {
      setMensagem('Saldo inválido. Use o formato 1.234,56, até 90.071.992.547.409,91.')
      return
    }
    emVoo.current = true
    setEnviando(true)
    setMensagem(null)
    try {
      const conta = await postConta({ nome, tipo, saldoInicialCentavos })
      aoCriar(conta)
      setNome('')
      setTipo('corrente')
      setSaldo('')
    } catch (erro) {
      if (erro instanceof SessaoExpiradaError) {
        aoSessaoExpirar()
        return
      }
      setMensagem(mensagemDeCriacao(erro))
    } finally {
      emVoo.current = false
      setEnviando(false)
    }
  }

  const descricao = mensagem === null ? undefined : 'erro-nova-conta'

  return (
    <form onSubmit={(evento) => void enviar(evento)} aria-label="Nova conta">
      <h3>Nova conta</h3>
      <div className="campo">
        <label htmlFor="conta-nome">Nome</label>
        <input
          id="conta-nome"
          type="text"
          maxLength={100}
          required
          value={nome}
          aria-describedby={descricao}
          onChange={(evento) => setNome(evento.target.value)}
        />
      </div>
      <div className="campo">
        <label htmlFor="conta-tipo">Tipo</label>
        <select
          id="conta-tipo"
          value={tipo}
          onChange={(evento) => {
            const escolhido = TIPOS.find((opcao) => opcao === evento.target.value)
            if (escolhido !== undefined) setTipo(escolhido)
          }}
        >
          {TIPOS.map((opcao) => (
            <option key={opcao} value={opcao}>
              {ROTULO_DO_TIPO[opcao]}
            </option>
          ))}
        </select>
      </div>
      <div className="campo">
        <label htmlFor="conta-saldo">Saldo inicial em reais (opcional)</label>
        <input
          id="conta-saldo"
          type="text"
          inputMode="decimal"
          autoComplete="off"
          placeholder="0,00"
          value={saldo}
          aria-describedby={descricao}
          onChange={(evento) => setSaldo(evento.target.value)}
        />
      </div>
      {mensagem !== null && (
        <p id="erro-nova-conta" role="alert" className="erro">
          {mensagem}
        </p>
      )}
      <button type="submit" disabled={enviando}>
        {enviando ? 'Criando...' : 'Criar conta'}
      </button>
    </form>
  )
}

export default function Contas({
  aoSessaoExpirar,
  versaoDeSaldo,
  aoCarregarAtivas,
}: {
  aoSessaoExpirar: () => void
  // Sobe quando um lançamento muda; a lista busca de novo para o saldo reagir.
  versaoDeSaldo: number
  // Entrega a lista de contas ativas sempre que ela muda, para a tela de lançamentos usar.
  aoCarregarAtivas: (contas: Conta[]) => void
}) {
  const [arquivadas, setArquivadas] = useState(false)
  const [estado, setEstado] = useState<EstadoLista>({ tipo: 'carregando' })
  const [tentativa, setTentativa] = useState(0)
  const [ocupada, setOcupada] = useState<string | null>(null)
  const [falhaDeAcao, setFalhaDeAcao] = useState<string | null>(null)
  const acaoEmVoo = useRef(false)

  useEffect(() => {
    // Descarta a resposta da lista anterior se o usuário já trocou de visão.
    let ativo = true
    fetchContas(arquivadas).then(
      (contas) => {
        if (ativo) setEstado({ tipo: 'ok', contas })
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
  }, [arquivadas, tentativa, versaoDeSaldo, aoSessaoExpirar])

  useEffect(() => {
    if (!arquivadas && estado.tipo === 'ok') aoCarregarAtivas(estado.contas)
  }, [arquivadas, estado, aoCarregarAtivas])

  function recarregar(): void {
    setEstado({ tipo: 'carregando' })
    setTentativa((n) => n + 1)
  }

  function alternarVisao(): void {
    setFalhaDeAcao(null)
    setEstado({ tipo: 'carregando' })
    setArquivadas((valor) => !valor)
  }

  async function mudarArquivamento(conta: Conta, arquivar: boolean): Promise<void> {
    if (acaoEmVoo.current) return
    acaoEmVoo.current = true
    setOcupada(conta.id)
    setFalhaDeAcao(null)
    try {
      await patchConta(conta.id, arquivar)
      // Na visão de arquivadas o efeito de entrega não roda, então busca as ativas aqui.
      if (arquivadas) {
        try {
          aoCarregarAtivas(await fetchContas(false))
        } catch (erro) {
          if (erro instanceof SessaoExpiradaError) {
            aoSessaoExpirar()
            return
          }
          setFalhaDeAcao('A conta mudou, mas não foi possível atualizar a lista de lançamentos.')
        }
      }
      // A conta troca de lista, então some desta sem precisar buscar de novo.
      setEstado((atual) =>
        atual.tipo === 'ok'
          ? { tipo: 'ok', contas: atual.contas.filter((outra) => outra.id !== conta.id) }
          : atual,
      )
    } catch (erro) {
      if (erro instanceof SessaoExpiradaError) {
        aoSessaoExpirar()
        return
      }
      setFalhaDeAcao(mensagemDeArquivamento(erro))
      // A lista na tela está velha: busca de novo o que o servidor tem.
      if (erro instanceof ContaNaoEncontradaError) recarregar()
    } finally {
      acaoEmVoo.current = false
      setOcupada(null)
    }
  }

  // BigInt porque a soma de várias contas pode passar do inteiro seguro.
  const total =
    estado.tipo === 'ok' ? estado.contas.reduce((soma, conta) => soma + BigInt(conta.saldoCentavos), 0n) : 0n

  return (
    <section aria-labelledby="titulo-contas">
      <h2 id="titulo-contas">{arquivadas ? 'Contas arquivadas' : 'Contas'}</h2>
      <button type="button" onClick={alternarVisao}>
        {arquivadas ? 'Voltar às ativas' : 'Ver arquivadas'}
      </button>
      {estado.tipo === 'carregando' && <p>Carregando contas...</p>}
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
      {falhaDeAcao !== null && (
        <p role="alert" className="erro">
          {falhaDeAcao}
        </p>
      )}
      {estado.tipo === 'ok' && (
        <>
          {!arquivadas && estado.contas.length > 0 && (
            <p>
              Total: <strong>{formatarCentavos(total)}</strong>
            </p>
          )}
          {estado.contas.length === 0 && (
            <p>{arquivadas ? 'Nenhuma conta arquivada.' : 'Nenhuma conta ainda.'}</p>
          )}
          <ul className="contas">
            {estado.contas.map((conta) => (
              <li key={conta.id}>
                <div className="conta-dados">
                  <strong>{conta.nome}</strong>
                  <span>{ROTULO_DO_TIPO[conta.tipo]}</span>
                  <span>{formatarCentavos(conta.saldoCentavos)}</span>
                </div>
                <button
                  type="button"
                  disabled={ocupada !== null}
                  aria-label={`${arquivadas ? 'Desarquivar' : 'Arquivar'} ${conta.nome}`}
                  onClick={() => void mudarArquivamento(conta, !arquivadas)}
                >
                  {arquivadas ? 'Desarquivar' : 'Arquivar'}
                </button>
              </li>
            ))}
          </ul>
        </>
      )}
      {!arquivadas && (
        <NovaConta
          aoCriar={(conta) =>
            setEstado((atual) =>
              atual.tipo === 'ok' ? { tipo: 'ok', contas: [...atual.contas, conta].sort(compararContas) } : atual,
            )
          }
          aoSessaoExpirar={aoSessaoExpirar}
        />
      )}
    </section>
  )
}
