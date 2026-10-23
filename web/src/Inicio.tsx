import { useEffect, useState } from 'react'
import { ServicoIndisponivelError, SessaoExpiradaError, fetchLancamentos } from './api'
import type { Conta, Lancamento, Usuario } from './api'
import { formatarDiaMes, hojeLocal, somarDias } from './datas'
import { formatarCentavos } from './dinheiro'
import SaldoProjetado from './SaldoProjetado'
import { aindaNaoAconteceu } from './totaisDoMes'
import { Carregando, EstadoVazio } from './ui'

const DIAS_A_FRENTE = 30
const MAXIMO_NA_LISTA = 6

type EstadoPrevistas =
  | { tipo: 'carregando' }
  | { tipo: 'erro'; mensagem: string }
  | { tipo: 'ok'; previstas: Lancamento[] }

function mensagemDeLeitura(erro: unknown): string {
  if (erro instanceof ServicoIndisponivelError) return 'Serviço indisponível. Tente de novo em instantes.'
  return 'Não foi possível carregar as próximas previstas.'
}

function Destaque({ contas }: { contas: Conta[] | null }) {
  if (contas === null) {
    return (
      <section className="cartao" aria-label="Resumo das contas">
        <Carregando texto="Carregando o resumo das contas..." linhas={2} />
      </section>
    )
  }
  if (contas.length === 0) {
    return (
      <section className="cartao" aria-label="Resumo das contas">
        <EstadoVazio
          titulo="Cadastre sua primeira conta."
          dica="O saldo e a projeção aparecem assim que houver uma conta."
          acao={
            <a className="botao primario" href="#/contas">
              Ir para Contas
            </a>
          }
        />
      </section>
    )
  }
  // O benefício só paga o que a lista permite e não cobre aluguel, então tem cartão próprio (ADR-034).
  const abertas = contas.filter((conta) => conta.tipo !== 'beneficio')
  // BigInt porque a soma de várias contas pode passar do inteiro seguro.
  const saldo = abertas.reduce((soma, conta) => soma + BigInt(conta.saldoCentavos), 0n)
  const cartoes = abertas.flatMap((conta) => (conta.cartao === null ? [] : [conta.cartao]))
  const disponivel = cartoes.reduce((soma, cartao) => soma + BigInt(cartao.disponivelCentavos), 0n)
  return (
    <section className="cartao destaque" aria-label="Resumo das contas">
      <p className="destaque-rotulo">Saldo nas contas ativas</p>
      <p className="destaque-valor">{formatarCentavos(saldo)}</p>
      <dl className="destaque-detalhes">
        <div>
          <dt>Contas ativas</dt>
          <dd>{abertas.length}</dd>
        </div>
        {cartoes.length > 0 && (
          <div>
            <dt>Disponível nos cartões</dt>
            <dd>{formatarCentavos(disponivel)}</dd>
          </div>
        )}
      </dl>
    </section>
  )
}

function Beneficios({ contas }: { contas: Conta[] | null }) {
  const beneficios = contas?.filter((conta) => conta.tipo === 'beneficio') ?? []
  if (beneficios.length === 0) return null
  const saldo = beneficios.reduce((soma, conta) => soma + BigInt(conta.saldoCentavos), 0n)
  return (
    <section className="cartao beneficios" aria-labelledby="titulo-beneficios">
      <h3 id="titulo-beneficios" className="titulo-cartao">
        Benefícios
      </h3>
      <p className="beneficios-valor">{formatarCentavos(saldo)}</p>
      {/* Com uma conta só, a lista repetiria o total. */}
      {beneficios.length > 1 && (
      <ul className="beneficios-lista" aria-label="Contas de benefício">
        {beneficios.map((conta) => (
          <li key={conta.id}>
            <span>{conta.nome}</span>
            <span className="beneficios-saldo">{formatarCentavos(conta.saldoCentavos)}</span>
          </li>
        ))}
      </ul>
      )}
    </section>
  )
}

function ProximasPrevistas({
  contas,
  versao,
  aoSessaoExpirar,
}: {
  contas: Conta[] | null
  // Sobe quando uma ocorrência foi confirmada, cancelada ou criada em outra tela.
  versao: number
  aoSessaoExpirar: () => void
}) {
  const [estado, setEstado] = useState<EstadoPrevistas>({ tipo: 'carregando' })
  const [tentativa, setTentativa] = useState(0)

  useEffect(() => {
    // Descarta a resposta se a tela já saiu do Início ou pediu de novo.
    let ativo = true
    const hoje = hojeLocal()
    fetchLancamentos(hoje, somarDias(hoje, DIAS_A_FRENTE)).then(
      (lancamentos) => {
        if (!ativo) return
        // Transferência não é ganho nem gasto; fica fora como no total do mês.
        const previstas = lancamentos
          .filter((lancamento) => aindaNaoAconteceu(lancamento, hoje) && lancamento.tipo !== 'transferencia')
          .sort((a, b) => (a.dataCaixa < b.dataCaixa ? -1 : a.dataCaixa > b.dataCaixa ? 1 : 0))
        setEstado({ tipo: 'ok', previstas })
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
  }, [tentativa, versao, aoSessaoExpirar])

  const nomesDeConta = new Map<string, string>()
  if (contas !== null) contas.forEach((conta) => nomesDeConta.set(conta.id, conta.nome))

  return (
    <section className="cartao" aria-labelledby="titulo-proximas-previstas">
      <h3 id="titulo-proximas-previstas" className="titulo-cartao">
        Próximas previstas
      </h3>
      {estado.tipo === 'carregando' && <Carregando texto="Carregando as próximas previstas..." linhas={3} />}
      {estado.tipo === 'erro' && (
        <>
          <p role="alert" className="erro">
            {estado.mensagem}
          </p>
          <button
            type="button"
            onClick={() => {
              setEstado({ tipo: 'carregando' })
              setTentativa((n) => n + 1)
            }}
          >
            Tentar de novo
          </button>
        </>
      )}
      {estado.tipo === 'ok' && estado.previstas.length === 0 && (
        <EstadoVazio
          titulo="Nada previsto para os próximos 30 dias."
          dica="Uma recorrência gera as contas que se repetem."
          acao={
            <a className="botao" href="#/recorrencias">
              Ver recorrências
            </a>
          }
        />
      )}
      {estado.tipo === 'ok' && estado.previstas.length > 0 && (
        <>
          <ul className="previstas-lista" aria-label="Próximas previstas">
            {estado.previstas.slice(0, MAXIMO_NA_LISTA).map((prevista) => (
              <li key={prevista.id}>
                <span className="previstas-data">{formatarDiaMes(prevista.dataCaixa)}</span>
                <div className="item-corpo">
                  <span className="item-titulo">{prevista.titulo}</span>
                  <span className="item-meta">{nomesDeConta.get(prevista.contaId) ?? 'Conta arquivada'}</span>
                </div>
                <span className={`item-valor ${prevista.tipo}`}>
                  {prevista.tipo === 'receita' ? '+' : '−'} {formatarCentavos(prevista.valorCentavos)}
                </span>
              </li>
            ))}
          </ul>
          <p className="cartao-rodape">
            <a href="#/lancamentos">Ver todos os lançamentos</a>
          </p>
        </>
      )}
    </section>
  )
}

export default function Inicio({
  usuario,
  contas,
  versaoDeSaldo,
  versaoDaLista,
  aoSessaoExpirar,
  aoMontarVencidas,
}: {
  usuario: Usuario
  // Contas ativas vindas da tela de Contas; null até a primeira carga.
  contas: Conta[] | null
  versaoDeSaldo: number
  versaoDaLista: number
  aoSessaoExpirar: () => void
  // Entrega o elemento onde a tela de Lançamentos desenha as vencidas (e null ao sair do Início).
  aoMontarVencidas: (elemento: HTMLElement | null) => void
}) {
  return (
    <section aria-labelledby="titulo-inicio">
      <header className="pagina-topo">
        <h2 id="titulo-inicio">Início</h2>
        <p className="legenda">
          Olá, <strong>{usuario.nome ?? usuario.email}</strong>
          {usuario.nome !== null && <span className="email">{usuario.email}</span>}
        </p>
      </header>
      <div className="inicio-grade">
        <div className="coluna">
          <Destaque contas={contas} />
          <Beneficios contas={contas} />
          <SaldoProjetado aoSessaoExpirar={aoSessaoExpirar} contas={contas} versaoDeSaldo={versaoDeSaldo} />
        </div>
        <div className="coluna">
          <div ref={aoMontarVencidas} />
          <ProximasPrevistas contas={contas} versao={versaoDaLista} aoSessaoExpirar={aoSessaoExpirar} />
        </div>
      </div>
    </section>
  )
}
