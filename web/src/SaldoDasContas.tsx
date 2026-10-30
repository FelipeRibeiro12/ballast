import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import {
  SessaoExpiradaError,
  deleteVisaoSaldo,
  fetchPatrimonio,
  fetchVisoesSaldo,
  postVisaoSaldo,
  putVisaoSaldo,
} from './api'
import type { Conta, Patrimonio, TipoDeConta, VisaoSaldo } from './api'
import { hojeLocal } from './datas'
import { formatarCentavos, formatarCentavosEmDolar } from './dinheiro'
import { IconeEngrenagem, IconeMais } from './icones'
import { carregar, mensagemDeEscrita } from './leitura'
import type { Estado } from './leitura'
import Painel from './Painel'
import { Carregando } from './ui'

export const ROTULO_DO_TIPO_DE_SALDO: Record<TipoDeConta, string> = {
  corrente: 'Corrente',
  poupanca: 'Poupança',
  dinheiro: 'Dinheiro',
  cartao_credito: 'Cartão de crédito',
  beneficio: 'Benefício',
  cripto: 'Cripto',
  investimento: 'Investimentos',
  cofrinho: 'Cofrinho',
}

const TODOS_OS_TIPOS = Object.keys(ROTULO_DO_TIPO_DE_SALDO) as TipoDeConta[]

// Teto de visões por usuário; o servidor é quem recusa a 11ª (ADR-068).
const MAXIMO_DE_VISOES = 10

// Cripto e investimentos não têm saldo em reais na conta (ADR-035, ADR-037), então vêm do patrimônio.
// BigInt porque a soma passa do inteiro seguro (ADR-023).
export function valorDaVisao(contas: Conta[], patrimonio: Patrimonio | null, tipos: readonly TipoDeConta[]): bigint {
  let soma = contas
    .filter((conta) => tipos.includes(conta.tipo))
    .reduce((total, conta) => total + BigInt(conta.saldoCentavos), 0n)
  if (patrimonio !== null) {
    if (tipos.includes('cripto')) soma += BigInt(patrimonio.criptoCentavos)
    if (tipos.includes('investimento')) soma += BigInt(patrimonio.rendaFixaCentavos)
  }
  return soma
}

export function precisaDoPatrimonio(tipos: readonly TipoDeConta[]): boolean {
  return tipos.includes('cripto') || tipos.includes('investimento')
}

function descreverTipos(tipos: readonly TipoDeConta[]): string {
  return tipos.map((tipo) => ROTULO_DO_TIPO_DE_SALDO[tipo]).join(', ')
}

function FormularioDeVisao({
  inicial,
  aoSalvar,
  aoSessaoExpirar,
}: {
  inicial: VisaoSaldo | null
  aoSalvar: () => void
  aoSessaoExpirar: () => void
}) {
  const [titulo, setTitulo] = useState(inicial?.titulo ?? '')
  const [tipos, setTipos] = useState<TipoDeConta[]>(inicial?.tipos ?? [])
  const [enviando, setEnviando] = useState(false)
  const [mensagem, setMensagem] = useState<string | null>(null)
  const emVoo = useRef(false)

  function alternar(tipo: TipoDeConta): void {
    setTipos((atuais) => (atuais.includes(tipo) ? atuais.filter((t) => t !== tipo) : [...atuais, tipo]))
  }

  async function enviar(evento: FormEvent<HTMLFormElement>): Promise<void> {
    evento.preventDefault()
    if (emVoo.current) return
    if (tipos.length === 0) {
      setMensagem('Escolha ao menos um tipo de conta.')
      return
    }
    emVoo.current = true
    setEnviando(true)
    setMensagem(null)
    try {
      if (inicial === null) await postVisaoSaldo(titulo.trim(), tipos)
      else await putVisaoSaldo(inicial.id, titulo.trim(), tipos)
      aoSalvar()
    } catch (erro) {
      if (erro instanceof SessaoExpiradaError) {
        aoSessaoExpirar()
        return
      }
      setMensagem(mensagemDeEscrita(erro, 'Não foi possível salvar. Tente de novo.'))
    } finally {
      emVoo.current = false
      setEnviando(false)
    }
  }

  return (
    <form onSubmit={(evento) => void enviar(evento)} aria-label="Visão de saldo">
      <div className="campo">
        <label htmlFor="visao-titulo">Título</label>
        <input
          id="visao-titulo"
          type="text"
          maxLength={100}
          required
          autoComplete="off"
          value={titulo}
          onChange={(evento) => setTitulo(evento.target.value)}
        />
      </div>
      <fieldset className="posso-chaves">
        <legend>Tipos de conta que entram</legend>
        {TODOS_OS_TIPOS.map((tipo) => (
          <label key={tipo} className="posso-chave">
            <input type="checkbox" checked={tipos.includes(tipo)} onChange={() => alternar(tipo)} />
            {ROTULO_DO_TIPO_DE_SALDO[tipo]}
          </label>
        ))}
      </fieldset>
      {mensagem !== null && (
        <p role="alert" className="erro">
          {mensagem}
        </p>
      )}
      <button type="submit" className="primario" disabled={enviando}>
        Salvar
      </button>
    </form>
  )
}

type Modo = { tipo: 'lista' } | { tipo: 'formulario'; visao: VisaoSaldo | null }

function GerenciarVisoes({
  visoes,
  aoMudar,
  aoSessaoExpirar,
}: {
  visoes: VisaoSaldo[]
  aoMudar: () => void
  aoSessaoExpirar: () => void
}) {
  const [modo, setModo] = useState<Modo>({ tipo: 'lista' })
  const [confirmando, setConfirmando] = useState<string | null>(null)
  const [falha, setFalha] = useState<string | null>(null)

  async function apagar(visao: VisaoSaldo): Promise<void> {
    setFalha(null)
    try {
      await deleteVisaoSaldo(visao.id)
      setConfirmando(null)
      aoMudar()
    } catch (erro) {
      if (erro instanceof SessaoExpiradaError) {
        aoSessaoExpirar()
        return
      }
      setConfirmando(null)
      setFalha(mensagemDeEscrita(erro, 'Não foi possível apagar. Tente de novo.'))
    }
  }

  if (modo.tipo === 'formulario') {
    return (
      <FormularioDeVisao
        inicial={modo.visao}
        aoSalvar={() => {
          setModo({ tipo: 'lista' })
          aoMudar()
        }}
        aoSessaoExpirar={aoSessaoExpirar}
      />
    )
  }

  return (
    <>
      <ul className="lancamentos lista-cartao" aria-label="Visões de saldo">
        {visoes.map((visao) => (
          <li key={visao.id}>
            <div className="item-corpo">
              <strong className="item-titulo">{visao.titulo}</strong>
              <small>{descreverTipos(visao.tipos)}</small>
            </div>
            <div className="item-lado">
              {confirmando === visao.id ? (
                <div className="item-botoes">
                  <button
                    type="button"
                    className="pequeno perigo"
                    aria-label={`Confirmar excluir ${visao.titulo}`}
                    onClick={() => void apagar(visao)}
                  >
                    Excluir
                  </button>
                  <button type="button" className="pequeno" onClick={() => setConfirmando(null)}>
                    Manter
                  </button>
                </div>
              ) : (
                <div className="item-botoes">
                  <button
                    type="button"
                    className="pequeno"
                    aria-label={`Editar ${visao.titulo}`}
                    onClick={() => setModo({ tipo: 'formulario', visao })}
                  >
                    Editar
                  </button>
                  <button
                    type="button"
                    className="pequeno perigo"
                    aria-label={`Excluir ${visao.titulo}`}
                    disabled={visoes.length <= 1}
                    onClick={() => {
                      setFalha(null)
                      setConfirmando(visao.id)
                    }}
                  >
                    Excluir
                  </button>
                </div>
              )}
            </div>
          </li>
        ))}
      </ul>
      {falha !== null && (
        <p role="alert" className="erro">
          {falha}
        </p>
      )}
      <button
        type="button"
        className="primario"
        disabled={visoes.length >= MAXIMO_DE_VISOES}
        onClick={() => setModo({ tipo: 'formulario', visao: null })}
      >
        <IconeMais />
        Adicionar visão
      </button>
      {visoes.length >= MAXIMO_DE_VISOES && <small>Limite de {MAXIMO_DE_VISOES} visões.</small>}
    </>
  )
}

// Carrossel de cartões azuis, um por visão. A engrenagem abre o gerenciador (ADR-068).
export default function SaldoDasContas({
  contas,
  aoSessaoExpirar,
}: {
  contas: Conta[]
  aoSessaoExpirar: () => void
}) {
  const [visoes, setVisoes] = useState<Estado<VisaoSaldo[]>>({ tipo: 'carregando' })
  const [versao, setVersao] = useState(0)
  const [patrimonio, setPatrimonio] = useState<Patrimonio | null>(null)
  const [patrimonioFalhou, setPatrimonioFalhou] = useState(false)
  const [gerenciando, setGerenciando] = useState(false)

  useEffect(() => {
    let vale = true
    carregar(fetchVisoesSaldo, 'os saldos', aoSessaoExpirar, setVisoes, () => vale)
    return () => {
      vale = false
    }
  }, [versao, aoSessaoExpirar])

  useEffect(() => {
    let vale = true
    // Sem o patrimônio os cartões de cripto e investimentos avisam, e o resto segue.
    fetchPatrimonio(hojeLocal()).then(
      (dados) => {
        if (vale) {
          setPatrimonio(dados)
          setPatrimonioFalhou(false)
        }
      },
      () => {
        if (vale) setPatrimonioFalhou(true)
      },
    )
    return () => {
      vale = false
    }
  }, [contas])

  const lista = visoes.tipo === 'ok' ? visoes.dados : []

  return (
    <section className="saldo-secao" aria-label="Resumo das contas">
      <header className="saldo-topo">
        <h3 className="titulo-cartao">Saldo</h3>
        <button
          type="button"
          className="icone-botao"
          aria-label="Configurar os saldos"
          disabled={visoes.tipo !== 'ok'}
          onClick={() => setGerenciando(true)}
        >
          <IconeEngrenagem />
        </button>
      </header>
      {visoes.tipo === 'carregando' && <Carregando texto="Carregando os saldos..." linhas={2} />}
      {visoes.tipo === 'erro' && (
        <>
          <p role="alert" className="erro">
            {visoes.mensagem}
          </p>
          <button type="button" onClick={() => setVersao((n) => n + 1)}>
            Tentar de novo
          </button>
        </>
      )}
      {visoes.tipo === 'ok' && (
        <div className="saldo-carrossel" role="group" aria-label="Totais das contas">
          {lista.map((visao) => (
            <div key={visao.id} className="cartao destaque saldo-visao">
              <p className="destaque-rotulo">{visao.titulo}</p>
              <p className="destaque-valor">{formatarCentavos(valorDaVisao(contas, patrimonio, visao.tipos))}</p>
              {visao.tipos.includes('cripto') && patrimonio?.criptoUsdCentavos != null && (
                <p className="saldo-tipos">Cripto em dólar {formatarCentavosEmDolar(patrimonio.criptoUsdCentavos)}</p>
              )}
              <p className="saldo-tipos">{descreverTipos(visao.tipos)}</p>
              {patrimonioFalhou && precisaDoPatrimonio(visao.tipos) && (
                <p className="saldo-tipos" role="status">
                  Cripto e investimentos indisponíveis agora; o valor não os inclui.
                </p>
              )}
            </div>
          ))}
        </div>
      )}
      {gerenciando && visoes.tipo === 'ok' && (
        <Painel titulo="Saldos" aoFechar={() => setGerenciando(false)}>
          <GerenciarVisoes
            visoes={lista}
            aoMudar={() => setVersao((n) => n + 1)}
            aoSessaoExpirar={aoSessaoExpirar}
          />
        </Painel>
      )}
    </section>
  )
}
