import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import {
  LancamentoInvalidoError,
  LancamentoNaoEncontradoError,
  ServicoIndisponivelError,
  SessaoExpiradaError,
  deleteLancamento,
  fetchCategorias,
  fetchLancamentos,
  postLancamento,
  putLancamento,
} from './api'
import type { Categoria, Conta, Lancamento } from './api'
import { formatarDiaMes, hojeLocal, intervaloDoMes, mesDeHoje, rotuloDoMes, somarMeses } from './datas'
import type { Mes } from './datas'
import { formatarCentavos, lerValorPositivo } from './dinheiro'

type TipoDeLancamento = Lancamento['tipo']

const TIPOS: TipoDeLancamento[] = ['receita', 'despesa']

const ROTULO_DO_TIPO: Record<TipoDeLancamento, string> = {
  receita: 'Receita',
  despesa: 'Despesa',
}

const MENSAGEM_INDISPONIVEL = 'Serviço indisponível. Tente de novo em instantes.'
const MENSAGEM_NAO_EXISTE = 'Esse lançamento não existe mais.'

// O servidor aceita datas de 1900 a 2199 (ADR-024); a navegação para nesses limites.
const ANO_MINIMO = 1900
const ANO_MAXIMO = 2199

type EstadoLista =
  | { tipo: 'carregando' }
  | { tipo: 'erro'; mensagem: string }
  | { tipo: 'ok'; lancamentos: Lancamento[] }

type EstadoCategorias =
  | { tipo: 'carregando' }
  | { tipo: 'erro'; mensagem: string }
  | { tipo: 'ok'; categorias: Categoria[] }

function mensagemDeLeitura(erro: unknown, alvo: string): string {
  if (erro instanceof ServicoIndisponivelError) return MENSAGEM_INDISPONIVEL
  return `Não foi possível carregar ${alvo}.`
}

function mensagemDeGravacao(erro: unknown): string {
  if (erro instanceof LancamentoInvalidoError && erro.mensagemServidor !== null) {
    return erro.mensagemServidor
  }
  if (erro instanceof ServicoIndisponivelError) return MENSAGEM_INDISPONIVEL
  return 'Não foi possível salvar o lançamento. Tente de novo.'
}

function mensagemDeExclusao(erro: unknown): string {
  if (erro instanceof LancamentoNaoEncontradoError) return MENSAGEM_NAO_EXISTE
  if (erro instanceof ServicoIndisponivelError) return MENSAGEM_INDISPONIVEL
  return 'Não foi possível apagar o lançamento. Tente de novo.'
}

function FormularioDeLancamento({
  contas,
  categorias,
  inicial,
  aoSalvar,
  aoNaoExistir,
  aoCancelar,
  aoSessaoExpirar,
}: {
  contas: Conta[]
  categorias: Categoria[]
  inicial: Lancamento | null
  aoSalvar: () => void
  aoNaoExistir: () => void
  aoCancelar: (() => void) | null
  aoSessaoExpirar: () => void
}) {
  const [tipo, setTipo] = useState<TipoDeLancamento>(inicial?.tipo ?? 'despesa')
  const [titulo, setTitulo] = useState(inicial?.titulo ?? '')
  const [valor, setValor] = useState(
    inicial === null ? '' : formatarCentavos(inicial.valorCentavos).replace(/[^\d.,]/g, ''),
  )
  const [categoriaEscolhida, setCategoriaEscolhida] = useState(inicial?.categoriaId ?? '')
  const [contaEscolhida, setContaEscolhida] = useState(inicial?.contaId ?? '')
  const [data, setData] = useState(inicial?.dataCaixa ?? hojeLocal())
  const [descricao, setDescricao] = useState(inicial?.descricao ?? '')
  const [enviando, setEnviando] = useState(false)
  const [mensagem, setMensagem] = useState<string | null>(null)
  // O state só atualiza no próximo render; o ref barra o segundo submit síncrono.
  const emVoo = useRef(false)

  const categoriasDoTipo = categorias.filter((categoria) => categoria.tipo === tipo)
  // Ao trocar o tipo a escolha antiga some da lista; cai na primeira em vez de guardar id inválido.
  const categoriaId = categoriasDoTipo.some((c) => c.id === categoriaEscolhida)
    ? categoriaEscolhida
    : (categoriasDoTipo[0]?.id ?? '')
  const contaId = contas.some((c) => c.id === contaEscolhida)
    ? contaEscolhida
    : // Lançamento numa conta arquivada continua editável sem trocar de conta.
      inicial !== null && contaEscolhida === inicial.contaId
      ? contaEscolhida
      : (contas[0]?.id ?? '')
  const contaArquivadaDoLancamento =
    inicial !== null && !contas.some((c) => c.id === inicial.contaId) ? inicial.contaId : null

  if (contas.length === 0 && contaArquivadaDoLancamento === null) {
    return <p>Crie uma conta antes de lançar.</p>
  }

  async function enviar(evento: FormEvent<HTMLFormElement>): Promise<void> {
    evento.preventDefault()
    if (emVoo.current) return
    const valorCentavos = lerValorPositivo(valor)
    if (valorCentavos === null) {
      setMensagem('Valor inválido. Use o formato 1.234,56, maior que zero e até 90.071.992.547.409,91.')
      return
    }
    emVoo.current = true
    setEnviando(true)
    setMensagem(null)
    const corpo = {
      tipo,
      titulo: titulo.trim(),
      ...(descricao.trim() === '' ? {} : { descricao: descricao.trim() }),
      valorCentavos,
      categoriaId,
      contaId,
      data,
    }
    try {
      if (inicial === null) {
        await postLancamento(corpo)
        setTitulo('')
        setValor('')
        setDescricao('')
      } else {
        await putLancamento(inicial.id, corpo)
      }
      aoSalvar()
    } catch (erro) {
      if (erro instanceof SessaoExpiradaError) {
        aoSessaoExpirar()
        return
      }
      if (erro instanceof LancamentoNaoEncontradoError) {
        // O formulário fecha junto com a edição, então o aviso vai para a tela, que não some.
        aoNaoExistir()
        return
      }
      setMensagem(mensagemDeGravacao(erro))
    } finally {
      emVoo.current = false
      setEnviando(false)
    }
  }

  const descricaoDoErro = mensagem === null ? undefined : 'erro-lancamento'
  const nome = inicial === null ? 'Novo lançamento' : 'Editar lançamento'

  return (
    <form onSubmit={(evento) => void enviar(evento)} aria-label={nome}>
      <h3>{nome}</h3>
      <div className="campo">
        <label htmlFor="lancamento-tipo">Tipo</label>
        <select
          id="lancamento-tipo"
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
        <label htmlFor="lancamento-titulo">Título</label>
        <input
          id="lancamento-titulo"
          type="text"
          maxLength={100}
          required
          value={titulo}
          aria-describedby={descricaoDoErro}
          onChange={(evento) => setTitulo(evento.target.value)}
        />
      </div>
      <div className="campo">
        <label htmlFor="lancamento-valor">Valor em reais</label>
        <input
          id="lancamento-valor"
          type="text"
          inputMode="decimal"
          autoComplete="off"
          placeholder="0,00"
          required
          value={valor}
          aria-describedby={descricaoDoErro}
          onChange={(evento) => setValor(evento.target.value)}
        />
      </div>
      <div className="campo">
        <label htmlFor="lancamento-categoria">Categoria</label>
        <select
          id="lancamento-categoria"
          value={categoriaId}
          required
          onChange={(evento) => setCategoriaEscolhida(evento.target.value)}
        >
          {categoriasDoTipo.map((categoria) => (
            <option key={categoria.id} value={categoria.id}>
              {categoria.nome}
            </option>
          ))}
        </select>
      </div>
      <div className="campo">
        <label htmlFor="lancamento-conta">Conta</label>
        <select
          id="lancamento-conta"
          value={contaId}
          required
          onChange={(evento) => setContaEscolhida(evento.target.value)}
        >
          {contaArquivadaDoLancamento !== null && (
            <option value={contaArquivadaDoLancamento}>Conta arquivada</option>
          )}
          {contas.map((conta) => (
            <option key={conta.id} value={conta.id}>
              {conta.nome}
            </option>
          ))}
        </select>
      </div>
      <div className="campo">
        <label htmlFor="lancamento-data">Data</label>
        <input
          id="lancamento-data"
          type="date"
          required
          min="1900-01-01"
          max="2199-12-31"
          value={data}
          onChange={(evento) => setData(evento.target.value)}
        />
      </div>
      <div className="campo">
        <label htmlFor="lancamento-descricao">Descrição (opcional)</label>
        <input
          id="lancamento-descricao"
          type="text"
          maxLength={500}
          value={descricao}
          onChange={(evento) => setDescricao(evento.target.value)}
        />
      </div>
      {mensagem !== null && (
        <p id="erro-lancamento" role="alert" className="erro">
          {mensagem}
        </p>
      )}
      <button type="submit" disabled={enviando}>
        {enviando ? 'Salvando...' : inicial === null ? 'Adicionar lançamento' : 'Salvar alterações'}
      </button>
      {aoCancelar !== null && (
        <button type="button" onClick={aoCancelar}>
          Cancelar edição
        </button>
      )}
    </form>
  )
}

export default function Lancamentos({
  aoSessaoExpirar,
  contas,
  aoMudarSaldo,
}: {
  aoSessaoExpirar: () => void
  // Contas ativas, vindas da tela de Contas; null até a primeira carga.
  contas: Conta[] | null
  // Avisa a lista de contas que o saldo mudou.
  aoMudarSaldo: () => void
}) {
  const [mes, setMes] = useState<Mes>(() => mesDeHoje())
  const [filtroDeConta, setFiltroDeConta] = useState('')
  const [estado, setEstado] = useState<EstadoLista>({ tipo: 'carregando' })
  const [apoio, setApoio] = useState<EstadoCategorias>({ tipo: 'carregando' })
  const [tentativa, setTentativa] = useState(0)
  const [tentativaDoApoio, setTentativaDoApoio] = useState(0)
  const [editando, setEditando] = useState<Lancamento | null>(null)
  const [confirmando, setConfirmando] = useState<string | null>(null)
  const [apagando, setApagando] = useState<string | null>(null)
  const [falhaDeAcao, setFalhaDeAcao] = useState<string | null>(null)
  // Muda a key do formulário de criação para limpar os campos depois de salvar.
  const [versaoDoFormulario, setVersaoDoFormulario] = useState(0)
  const acaoEmVoo = useRef(false)

  useEffect(() => {
    // Descarta a resposta do mês anterior se o usuário já navegou.
    let ativo = true
    const { de, ate } = intervaloDoMes(mes)
    fetchLancamentos(de, ate, filtroDeConta === '' ? undefined : filtroDeConta).then(
      (lancamentos) => {
        if (ativo) setEstado({ tipo: 'ok', lancamentos })
      },
      (erro: unknown) => {
        if (!ativo) return
        if (erro instanceof SessaoExpiradaError) {
          aoSessaoExpirar()
          return
        }
        setEstado({ tipo: 'erro', mensagem: mensagemDeLeitura(erro, 'os lançamentos') })
      },
    )
    return () => {
      ativo = false
    }
  }, [mes, filtroDeConta, tentativa, aoSessaoExpirar])

  useEffect(() => {
    let ativo = true
    fetchCategorias().then(
      (categorias) => {
        if (ativo) setApoio({ tipo: 'ok', categorias })
      },
      (erro: unknown) => {
        if (!ativo) return
        if (erro instanceof SessaoExpiradaError) {
          aoSessaoExpirar()
          return
        }
        setApoio({ tipo: 'erro', mensagem: mensagemDeLeitura(erro, 'as categorias') })
      },
    )
    return () => {
      ativo = false
    }
  }, [tentativaDoApoio, aoSessaoExpirar])

  function recarregar(): void {
    setEstado({ tipo: 'carregando' })
    setTentativa((n) => n + 1)
  }

  function depoisDeMudar(): void {
    recarregar()
    aoMudarSaldo()
  }

  function navegar(delta: number): void {
    setFalhaDeAcao(null)
    setConfirmando(null)
    setEstado({ tipo: 'carregando' })
    setMes((atual) => somarMeses(atual, delta))
  }

  async function apagar(lancamento: Lancamento): Promise<void> {
    if (acaoEmVoo.current) return
    acaoEmVoo.current = true
    setApagando(lancamento.id)
    setFalhaDeAcao(null)
    try {
      await deleteLancamento(lancamento.id)
      setConfirmando(null)
      if (editando?.id === lancamento.id) setEditando(null)
      depoisDeMudar()
    } catch (erro) {
      if (erro instanceof SessaoExpiradaError) {
        aoSessaoExpirar()
        return
      }
      setFalhaDeAcao(mensagemDeExclusao(erro))
      setConfirmando(null)
      // A lista na tela está velha: busca de novo o que o servidor tem.
      if (erro instanceof LancamentoNaoEncontradoError) depoisDeMudar()
    } finally {
      acaoEmVoo.current = false
      setApagando(null)
    }
  }

  const nomesDeConta = new Map<string, string>()
  const nomesDeCategoria = new Map<string, string>()
  if (contas !== null) contas.forEach((conta) => nomesDeConta.set(conta.id, conta.nome))
  if (apoio.tipo === 'ok') {
    apoio.categorias.forEach((categoria) => nomesDeCategoria.set(categoria.id, categoria.nome))
  }

  // BigInt porque a soma de muitos lançamentos pode passar do inteiro seguro.
  let receitas = 0n
  let despesas = 0n
  if (estado.tipo === 'ok') {
    for (const lancamento of estado.lancamentos) {
      if (lancamento.tipo === 'receita') receitas += BigInt(lancamento.valorCentavos)
      else despesas += BigInt(lancamento.valorCentavos)
    }
  }

  const noPrimeiroMes = mes.ano <= ANO_MINIMO && mes.mes === 1
  const noUltimoMes = mes.ano >= ANO_MAXIMO && mes.mes === 12
  const rotulo = rotuloDoMes(mes)

  return (
    <section aria-labelledby="titulo-lancamentos">
      <h2 id="titulo-lancamentos">Lançamentos</h2>
      <div className="mes-nav">
        <button type="button" aria-label="Mês anterior" disabled={noPrimeiroMes} onClick={() => navegar(-1)}>
          ‹
        </button>
        <strong aria-live="polite">{rotulo}</strong>
        <button type="button" aria-label="Próximo mês" disabled={noUltimoMes} onClick={() => navegar(1)}>
          ›
        </button>
      </div>
      {contas !== null && (
        <div className="campo">
          <label htmlFor="lancamentos-filtro-conta">Filtrar por conta</label>
          <select
            id="lancamentos-filtro-conta"
            value={filtroDeConta}
            onChange={(evento) => {
              setEstado({ tipo: 'carregando' })
              setFiltroDeConta(evento.target.value)
            }}
          >
            <option value="">Todas</option>
            {contas.map((conta) => (
              <option key={conta.id} value={conta.id}>
                {conta.nome}
              </option>
            ))}
          </select>
        </div>
      )}
      {estado.tipo === 'carregando' && <p>Carregando lançamentos...</p>}
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
          <dl className="totais">
            <div>
              <dt>Receitas</dt>
              <dd className="receita">{formatarCentavos(receitas)}</dd>
            </div>
            <div>
              <dt>Despesas</dt>
              <dd className="despesa">{formatarCentavos(despesas)}</dd>
            </div>
            <div>
              <dt>Resultado</dt>
              <dd>{formatarCentavos(receitas - despesas)}</dd>
            </div>
          </dl>
          {estado.lancamentos.length === 0 && <p>Nenhum lançamento neste mês.</p>}
          {estado.lancamentos.length > 0 && (
            <ul className="lancamentos">
              {estado.lancamentos.map((lancamento) => {
                const sinal = lancamento.tipo === 'receita' ? '+' : '−'
                return (
                  <li key={lancamento.id}>
                    <div className="lancamento-dados">
                      <span>{formatarDiaMes(lancamento.dataCaixa)}</span>
                      <strong>{lancamento.titulo}</strong>
                      <span>
                        {nomesDeCategoria.get(lancamento.categoriaId) ?? 'Sem categoria'}
                        {' · '}
                        {nomesDeConta.get(lancamento.contaId) ?? 'Conta arquivada'}
                      </span>
                      <span className={lancamento.tipo}>
                        {sinal} {formatarCentavos(lancamento.valorCentavos)}
                      </span>
                    </div>
                    <div className="lancamento-acoes">
                      <button
                        type="button"
                        disabled={apagando !== null}
                        aria-label={`Editar ${lancamento.titulo}`}
                        onClick={() => {
                          setFalhaDeAcao(null)
                          setConfirmando(null)
                          setEditando(lancamento)
                        }}
                      >
                        Editar
                      </button>
                      {confirmando === lancamento.id ? (
                        <>
                          <button
                            type="button"
                            disabled={apagando !== null}
                            aria-label={`Confirmar apagar ${lancamento.titulo}`}
                            onClick={() => void apagar(lancamento)}
                          >
                            Confirmar apagar
                          </button>
                          <button type="button" disabled={apagando !== null} onClick={() => setConfirmando(null)}>
                            Cancelar
                          </button>
                        </>
                      ) : (
                        <button
                          type="button"
                          disabled={apagando !== null}
                          aria-label={`Apagar ${lancamento.titulo}`}
                          onClick={() => setConfirmando(lancamento.id)}
                        >
                          Apagar
                        </button>
                      )}
                    </div>
                  </li>
                )
              })}
            </ul>
          )}
        </>
      )}
      {(apoio.tipo === 'carregando' || (apoio.tipo === 'ok' && contas === null)) && (
        <p>Preparando o formulário...</p>
      )}
      {apoio.tipo === 'erro' && (
        <>
          <p role="alert" className="erro">
            {apoio.mensagem}
          </p>
          <button
            type="button"
            onClick={() => {
              setApoio({ tipo: 'carregando' })
              setTentativaDoApoio((n) => n + 1)
            }}
          >
            Tentar de novo
          </button>
        </>
      )}
      {apoio.tipo === 'ok' && contas !== null && (
        <FormularioDeLancamento
          key={editando?.id ?? `novo-${versaoDoFormulario}`}
          contas={contas}
          categorias={apoio.categorias}
          inicial={editando}
          aoSalvar={() => {
            if (editando === null) setVersaoDoFormulario((n) => n + 1)
            setEditando(null)
            depoisDeMudar()
          }}
          aoNaoExistir={() => {
            setEditando(null)
            setFalhaDeAcao(MENSAGEM_NAO_EXISTE)
            depoisDeMudar()
          }}
          aoCancelar={editando === null ? null : () => setEditando(null)}
          aoSessaoExpirar={aoSessaoExpirar}
        />
      )}
    </section>
  )
}
