import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import {
  SessaoExpiradaError,
  deleteTerceiro,
  fetchCategorias,
  fetchContas,
  fetchLancamentosDoTerceiro,
  fetchTerceiros,
  ligarLancamentoAoTerceiro,
  postLancamento,
  postTerceiro,
  putTerceiro,
} from './api'
import type { Categoria, Conta, Terceiro, TerceiroLancamento } from './api'
import { hojeLocal } from './datas'
import { centavosParaCampo, formatarCentavos, lerValorPositivo } from './dinheiro'
import { IconeMais } from './icones'
import { carregar, mensagemDeEscrita } from './leitura'
import type { Estado } from './leitura'
import MenuDeAcoes from './MenuDeAcoes'
import Painel from './Painel'
import AtalhosDePlanejamento from './Planejamento'
import { Carregando, EstadoVazio, Selo } from './ui'

function FormularioDeNome({
  inicial,
  aoSalvar,
  aoSessaoExpirar,
}: {
  inicial: Terceiro | null
  aoSalvar: () => void
  aoSessaoExpirar: () => void
}) {
  const [nome, setNome] = useState(inicial?.nome ?? '')
  const [enviando, setEnviando] = useState(false)
  const [mensagem, setMensagem] = useState<string | null>(null)
  const emVoo = useRef(false)

  async function enviar(evento: FormEvent<HTMLFormElement>): Promise<void> {
    evento.preventDefault()
    if (emVoo.current) return
    emVoo.current = true
    setEnviando(true)
    setMensagem(null)
    try {
      if (inicial === null) await postTerceiro(nome.trim())
      else await putTerceiro(inicial.id, nome.trim(), inicial.arquivado)
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
    <form onSubmit={(evento) => void enviar(evento)} aria-label="Terceiro">
      <div className="campo">
        <label htmlFor="terceiro-nome">Nome</label>
        <input
          id="terceiro-nome"
          type="text"
          maxLength={100}
          required
          autoComplete="off"
          value={nome}
          onChange={(evento) => setNome(evento.target.value)}
        />
      </div>
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

// O reembolso é uma receita comum em Outras receitas, ligada ao terceiro em seguida (ADR-062).
function FormularioDeReembolso({
  terceiro,
  aoSalvar,
  aoSessaoExpirar,
}: {
  terceiro: Terceiro
  aoSalvar: () => void
  aoSessaoExpirar: () => void
}) {
  const [contas, setContas] = useState<Estado<Conta[]>>({ tipo: 'carregando' })
  const [categorias, setCategorias] = useState<Estado<Categoria[]>>({ tipo: 'carregando' })
  const [contaId, setContaId] = useState('')
  const [valor, setValor] = useState(terceiro.devidoCentavos > 0 ? centavosParaCampo(terceiro.devidoCentavos) : '')
  const [data, setData] = useState(() => hojeLocal())
  const [enviando, setEnviando] = useState(false)
  const [mensagem, setMensagem] = useState<string | null>(null)
  const emVoo = useRef(false)

  useEffect(() => {
    let vale = true
    carregar(() => fetchContas(false), 'as contas', aoSessaoExpirar, setContas, () => vale)
    carregar(fetchCategorias, 'as categorias', aoSessaoExpirar, setCategorias, () => vale)
    return () => {
      vale = false
    }
  }, [aoSessaoExpirar])

  const contasUteis =
    contas.tipo === 'ok'
      ? contas.dados.filter((c) => c.tipo !== 'cartao_credito' && c.tipo !== 'cripto' && c.tipo !== 'investimento')
      : []
  const escolhida = contasUteis.some((c) => c.id === contaId) ? contaId : (contasUteis[0]?.id ?? '')
  const outrasReceitas = categorias.tipo === 'ok' ? categorias.dados.find((c) => c.tipo === 'receita' && c.protegida) : undefined

  async function enviar(evento: FormEvent<HTMLFormElement>): Promise<void> {
    evento.preventDefault()
    if (emVoo.current) return
    const centavos = lerValorPositivo(valor)
    if (centavos === null) {
      setMensagem('Valor inválido. Use o formato 1.234,56.')
      return
    }
    if (escolhida === '' || outrasReceitas === undefined) {
      setMensagem('Espere as contas e categorias carregarem.')
      return
    }
    emVoo.current = true
    setEnviando(true)
    setMensagem(null)
    try {
      const receita = await postLancamento({
        tipo: 'receita',
        titulo: `Reembolso de ${terceiro.nome}`,
        valorCentavos: centavos,
        categoriaId: outrasReceitas.id,
        contaId: escolhida,
        data,
      })
      await ligarLancamentoAoTerceiro(receita.id, terceiro.id)
      aoSalvar()
    } catch (erro) {
      if (erro instanceof SessaoExpiradaError) {
        aoSessaoExpirar()
        return
      }
      setMensagem(mensagemDeEscrita(erro, 'Não foi possível registrar. Tente de novo.'))
    } finally {
      emVoo.current = false
      setEnviando(false)
    }
  }

  return (
    <form onSubmit={(evento) => void enviar(evento)} aria-label="Reembolso">
      <div className="campo">
        <label htmlFor="reembolso-valor">Valor recebido</label>
        <input
          id="reembolso-valor"
          type="text"
          inputMode="decimal"
          autoComplete="off"
          placeholder="0,00"
          required
          value={valor}
          onChange={(evento) => setValor(evento.target.value)}
        />
      </div>
      <div className="campo">
        <label htmlFor="reembolso-conta">Conta que recebeu</label>
        <select id="reembolso-conta" value={escolhida} onChange={(evento) => setContaId(evento.target.value)}>
          {contasUteis.map((conta) => (
            <option key={conta.id} value={conta.id}>
              {conta.nome}
            </option>
          ))}
        </select>
      </div>
      <div className="campo">
        <label htmlFor="reembolso-data">Data</label>
        <input id="reembolso-data" type="date" required value={data} onChange={(evento) => setData(evento.target.value)} />
      </div>
      {mensagem !== null && (
        <p role="alert" className="erro">
          {mensagem}
        </p>
      )}
      <button type="submit" className="primario" disabled={enviando}>
        Registrar reembolso
      </button>
    </form>
  )
}

function Detalhe({ terceiro, aoSessaoExpirar }: { terceiro: Terceiro; aoSessaoExpirar: () => void }) {
  const [estado, setEstado] = useState<Estado<TerceiroLancamento[]>>({ tipo: 'carregando' })

  useEffect(() => {
    let vale = true
    carregar(() => fetchLancamentosDoTerceiro(terceiro.id), 'os lançamentos', aoSessaoExpirar, setEstado, () => vale)
    return () => {
      vale = false
    }
  }, [terceiro, aoSessaoExpirar])

  if (estado.tipo === 'carregando') return <Carregando texto="Carregando lançamentos..." />
  if (estado.tipo === 'erro') {
    return (
      <p role="alert" className="erro">
        {estado.mensagem}
      </p>
    )
  }
  if (estado.dados.length === 0) return <EstadoVazio titulo="Nenhum lançamento ligado." dica="" />
  return (
    <ul className="lancamentos lista-cartao" aria-label={`Lançamentos de ${terceiro.nome}`}>
      {estado.dados.map((item) => (
        <li key={item.id}>
          <div className="item-corpo">
            <strong className="item-titulo">{item.titulo}</strong>
            <small>
              {item.dataCaixa.split('-').reverse().join('/')}
              {item.parcela !== null && item.totalParcelas !== null ? ` · parcela ${item.parcela}/${item.totalParcelas}` : ''}
            </small>
            <div className="item-selos">
              <Selo tom={item.tipo === 'receita' ? 'paga' : 'neutro'}>{item.tipo === 'receita' ? 'Reembolso' : 'Compra'}</Selo>
              {item.status === 'previsto' && <Selo tom="previsto">Previsto</Selo>}
            </div>
          </div>
          <div className="item-lado">
            <strong>{formatarCentavos(item.valorCentavos)}</strong>
          </div>
        </li>
      ))}
    </ul>
  )
}

type Abertura =
  | { tipo: 'novo' }
  | { tipo: 'renomear'; terceiro: Terceiro }
  | { tipo: 'reembolso'; terceiro: Terceiro }
  | { tipo: 'detalhe'; terceiro: Terceiro }

// Quem usa o cartão do dono e reembolsa depois (ADR-062). Fora de relatório, orçamento e Posso gastar.
export default function Terceiros({ aoSessaoExpirar }: { aoSessaoExpirar: () => void }) {
  const [versao, setVersao] = useState(0)
  const [estado, setEstado] = useState<Estado<Terceiro[]>>({ tipo: 'carregando' })
  const [abertura, setAbertura] = useState<Abertura | null>(null)
  const [confirmando, setConfirmando] = useState<string | null>(null)
  const [falha, setFalha] = useState<string | null>(null)

  useEffect(() => {
    let vale = true
    carregar(fetchTerceiros, 'os terceiros', aoSessaoExpirar, setEstado, () => vale)
    return () => {
      vale = false
    }
  }, [versao, aoSessaoExpirar])

  async function executar(acao: () => Promise<unknown>, padrao: string): Promise<void> {
    setFalha(null)
    try {
      await acao()
      setConfirmando(null)
      setVersao((n) => n + 1)
    } catch (erro) {
      if (erro instanceof SessaoExpiradaError) {
        aoSessaoExpirar()
        return
      }
      setConfirmando(null)
      setFalha(mensagemDeEscrita(erro, padrao))
    }
  }

  const terceiros = estado.tipo === 'ok' ? estado.dados : []

  return (
    <section aria-labelledby="titulo-terceiros">
      <header className="pagina-topo">
        <h2 id="titulo-terceiros">Terceiros</h2>
        <button
          type="button"
          className="primario botao-novo"
          disabled={estado.tipo !== 'ok'}
          onClick={() => setAbertura({ tipo: 'novo' })}
        >
          <IconeMais />
          Novo terceiro
        </button>
      </header>
      <AtalhosDePlanejamento atual="terceiros" />
      {estado.tipo === 'carregando' && <Carregando texto="Carregando terceiros..." />}
      {estado.tipo === 'erro' && (
        <p role="alert" className="erro">
          {estado.mensagem}
        </p>
      )}
      {falha !== null && (
        <p role="alert" className="erro">
          {falha}
        </p>
      )}
      {estado.tipo === 'ok' && terceiros.length === 0 && (
        <EstadoVazio
          titulo="Nenhum terceiro."
          dica="Cadastre quem usa seu cartão. Ligue a compra a ele ao lançar e registre os reembolsos aqui."
        />
      )}
      {estado.tipo === 'ok' && terceiros.length > 0 && (
        <ul className="lancamentos lista-cartao" aria-label="Terceiros">
          {terceiros.map((terceiro) => (
            <li key={terceiro.id}>
              <div className="item-corpo">
                <strong className="item-titulo">{terceiro.nome}</strong>
                <small>
                  Comprou {formatarCentavos(terceiro.compradoCentavos)} · pagou {formatarCentavos(terceiro.recebidoCentavos)}
                </small>
                <strong>
                  {terceiro.devidoCentavos > 0
                    ? `Deve ${formatarCentavos(terceiro.devidoCentavos)}`
                    : terceiro.devidoCentavos < 0
                      ? `Pagou a mais ${formatarCentavos(-terceiro.devidoCentavos)}`
                      : 'Em dia'}
                </strong>
                {terceiro.arquivado && (
                  <div className="item-selos">
                    <Selo tom="neutro">Arquivado</Selo>
                  </div>
                )}
              </div>
              <div className="item-lado">
                {confirmando === terceiro.id ? (
                  <div className="item-botoes">
                    <button
                      type="button"
                      className="pequeno perigo"
                      aria-label={`Confirmar apagar ${terceiro.nome}`}
                      onClick={() => void executar(() => deleteTerceiro(terceiro.id), 'Não foi possível apagar. Tente de novo.')}
                    >
                      Apagar
                    </button>
                    <button type="button" className="pequeno" onClick={() => setConfirmando(null)}>
                      Manter
                    </button>
                  </div>
                ) : (
                  <MenuDeAcoes rotulo={`Mais ações de ${terceiro.nome}`}>
                    <button type="button" onClick={() => setAbertura({ tipo: 'detalhe', terceiro })}>
                      Ver lançamentos
                    </button>
                    {!terceiro.arquivado && (
                      <button type="button" onClick={() => setAbertura({ tipo: 'reembolso', terceiro })}>
                        Registrar reembolso
                      </button>
                    )}
                    <button type="button" onClick={() => setAbertura({ tipo: 'renomear', terceiro })}>
                      Renomear
                    </button>
                    <button
                      type="button"
                      onClick={() =>
                        void executar(
                          () => putTerceiro(terceiro.id, terceiro.nome, !terceiro.arquivado),
                          'Não foi possível salvar. Tente de novo.',
                        )
                      }
                    >
                      {terceiro.arquivado ? 'Desarquivar' : 'Arquivar'}
                    </button>
                    <button
                      type="button"
                      className="perigo"
                      onClick={() => {
                        setFalha(null)
                        setConfirmando(terceiro.id)
                      }}
                    >
                      Apagar
                    </button>
                  </MenuDeAcoes>
                )}
              </div>
            </li>
          ))}
        </ul>
      )}
      {abertura !== null && (
        <Painel
          titulo={
            abertura.tipo === 'novo'
              ? 'Novo terceiro'
              : abertura.tipo === 'renomear'
                ? `Renomear ${abertura.terceiro.nome}`
                : abertura.tipo === 'reembolso'
                  ? `Reembolso de ${abertura.terceiro.nome}`
                  : abertura.terceiro.nome
          }
          aoFechar={() => setAbertura(null)}
        >
          {abertura.tipo === 'detalhe' ? (
            <Detalhe terceiro={abertura.terceiro} aoSessaoExpirar={aoSessaoExpirar} />
          ) : abertura.tipo === 'reembolso' ? (
            <FormularioDeReembolso
              terceiro={abertura.terceiro}
              aoSalvar={() => {
                setAbertura(null)
                setVersao((n) => n + 1)
              }}
              aoSessaoExpirar={aoSessaoExpirar}
            />
          ) : (
            <FormularioDeNome
              inicial={abertura.tipo === 'renomear' ? abertura.terceiro : null}
              aoSalvar={() => {
                setAbertura(null)
                setVersao((n) => n + 1)
              }}
              aoSessaoExpirar={aoSessaoExpirar}
            />
          )}
        </Painel>
      )}
    </section>
  )
}
