import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import {
  LancamentoInvalidoError,
  LancamentoNaoEncontradoError,
  ServicoIndisponivelError,
  SessaoExpiradaError,
  TransferenciaInvalidaError,
  TransferenciaNaoEncontradaError,
  deleteLancamento,
  deleteTransferencia,
  fetchCategorias,
  fetchLancamentos,
  fetchVencidos,
  patchStatusLancamento,
  postLancamento,
  postTransferencia,
  putLancamento,
} from './api'
import type { AjusteDeConfirmacao, Categoria, Conta, Lancamento } from './api'
import { vencimentoDaFatura } from './fatura'
import { formatarDataCompleta, formatarDiaMes, hojeLocal, intervaloDoMes, mesDeHoje, rotuloDoMes, somarMeses } from './datas'
import type { Mes } from './datas'
import { formatarCentavos, lerValorPositivo } from './dinheiro'

type TipoDeLancamento = Lancamento['tipo']

// O formulário de lançamento só cria receita e despesa; transferência tem formulário próprio.
type TipoEditavel = Exclude<TipoDeLancamento, 'transferencia'>

const TIPOS: TipoEditavel[] = ['receita', 'despesa']

const ROTULO_DO_TIPO: Record<TipoEditavel, string> = {
  receita: 'Receita',
  despesa: 'Despesa',
}

// O servidor aceita datas de 1900 a 2199 (ADR-024); a navegação para nesses limites.
const ANO_MINIMO = 1900
const ANO_MAXIMO = 2199

const MENSAGEM_INDISPONIVEL = 'Serviço indisponível. Tente de novo em instantes.'
const MENSAGEM_NAO_EXISTE = 'Esse lançamento não existe mais.'
const MENSAGEM_DATA_INVALIDA = `Data inválida. Use uma data de ${ANO_MINIMO} a ${ANO_MAXIMO}.`
const MENSAGEM_VALOR_INVALIDO =
  'Valor inválido. Use o formato 1.234,56, maior que zero e até 90.071.992.547.409,91.'

type EstadoLista =
  | { tipo: 'carregando' }
  | { tipo: 'erro'; mensagem: string }
  | { tipo: 'ok'; lancamentos: Lancamento[] }

type EstadoVencidas =
  | { tipo: 'carregando' }
  | { tipo: 'erro'; mensagem: string }
  | { tipo: 'ok'; lancamentos: Lancamento[]; maisAlgum: boolean }

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

function mensagemDeTransferencia(erro: unknown): string {
  if (erro instanceof TransferenciaInvalidaError && erro.mensagemServidor !== null) {
    return erro.mensagemServidor
  }
  if (erro instanceof ServicoIndisponivelError) return MENSAGEM_INDISPONIVEL
  return 'Não foi possível salvar a transferência. Tente de novo.'
}

function mensagemDeStatus(erro: unknown): string {
  if (erro instanceof LancamentoNaoEncontradoError) return MENSAGEM_NAO_EXISTE
  if (erro instanceof LancamentoInvalidoError && erro.mensagemServidor !== null) {
    return erro.mensagemServidor
  }
  if (erro instanceof ServicoIndisponivelError) return MENSAGEM_INDISPONIVEL
  return 'Não foi possível alterar a ocorrência. Tente de novo.'
}

function mensagemDeExclusao(erro: unknown): string {
  if (erro instanceof LancamentoNaoEncontradoError || erro instanceof TransferenciaNaoEncontradaError) {
    return MENSAGEM_NAO_EXISTE
  }
  if (erro instanceof TransferenciaInvalidaError && erro.mensagemServidor !== null) {
    return erro.mensagemServidor
  }
  if (erro instanceof LancamentoInvalidoError && erro.mensagemServidor !== null) {
    return erro.mensagemServidor
  }
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
  const [tipo, setTipo] = useState<TipoEditavel>(
    TIPOS.find((opcao) => opcao === inicial?.tipo) ?? 'despesa',
  )
  const [titulo, setTitulo] = useState(inicial?.titulo ?? '')
  const [valor, setValor] = useState(
    inicial === null ? '' : formatarCentavos(inicial.valorCentavos).replace(/[^\d.,]/g, ''),
  )
  const [categoriaEscolhida, setCategoriaEscolhida] = useState(inicial?.categoriaId ?? '')
  const [contaEscolhida, setContaEscolhida] = useState(inicial?.contaId ?? '')
  const [data, setData] = useState(inicial?.dataCompetencia ?? hojeLocal())
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
  const cartaoEscolhido = contas.find((c) => c.id === contaId)?.cartao ?? null
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
      setMensagem(MENSAGEM_VALOR_INVALIDO)
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
        <label htmlFor="lancamento-data">{cartaoEscolhido === null ? 'Data' : 'Data da compra'}</label>
        <input
          id="lancamento-data"
          type="date"
          required
          min="1900-01-01"
          max="2199-12-31"
          value={data}
          onChange={(evento) => setData(evento.target.value)}
        />
        {cartaoEscolhido !== null && /^\d{4}-\d{2}-\d{2}$/.test(data) && (
          <small>
            Entra na fatura que vence em{' '}
            {formatarDiaMes(vencimentoDaFatura(data, cartaoEscolhido.diaFechamento, cartaoEscolhido.diaVencimento))}
          </small>
        )}
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

// O input date já limita a faixa, mas digitar à mão ainda deixa passar texto fora dela.
function eDataNaFaixa(data: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(data)) return false
  const ano = Number(data.slice(0, 4))
  return ano >= ANO_MINIMO && ano <= ANO_MAXIMO
}

function FormularioDeConfirmacao({
  prevista,
  cartao,
  aoConfirmar,
  aoVoltar,
}: {
  prevista: Lancamento
  // Cartão da conta da prevista, ou null. Nele a data é a da compra e a caixa vem do servidor.
  cartao: Conta['cartao']
  // Devolve a mensagem de erro, ou null quando deu certo e o formulário vai fechar.
  aoConfirmar: (ajuste: AjusteDeConfirmacao) => Promise<string | null>
  aoVoltar: () => void
}) {
  const valorPrevisto = formatarCentavos(prevista.valorCentavos).replace(/[^\d.,]/g, '')
  const [valor, setValor] = useState(valorPrevisto)
  const [data, setData] = useState(prevista.dataCompetencia)
  const [enviando, setEnviando] = useState(false)
  const [mensagem, setMensagem] = useState<string | null>(null)
  // O state só atualiza no próximo render; o ref barra o segundo submit síncrono.
  const emVoo = useRef(false)

  async function enviar(evento: FormEvent<HTMLFormElement>): Promise<void> {
    evento.preventDefault()
    if (emVoo.current) return
    const valorCentavos = lerValorPositivo(valor)
    if (valorCentavos === null) {
      setMensagem(MENSAGEM_VALOR_INVALIDO)
      return
    }
    if (!eDataNaFaixa(data)) {
      setMensagem(MENSAGEM_DATA_INVALIDA)
      return
    }
    emVoo.current = true
    setEnviando(true)
    setMensagem(null)
    // Só o que mudou vai no corpo: sem mudança o pedido é igual ao de confirmar direto.
    const ajuste: AjusteDeConfirmacao = {
      ...(valorCentavos === prevista.valorCentavos ? {} : { valorCentavos }),
      ...(data === prevista.dataCompetencia ? {} : { data }),
    }
    try {
      setMensagem(await aoConfirmar(ajuste))
    } finally {
      emVoo.current = false
      setEnviando(false)
    }
  }

  const id = `confirmar-${prevista.id}`
  const descricaoDoErro = mensagem === null ? undefined : `${id}-erro`

  return (
    <form
      className="confirmacao"
      onSubmit={(evento) => void enviar(evento)}
      aria-label={`Confirmar ${prevista.titulo}`}
    >
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
        <label htmlFor={`${id}-data`}>{cartao === null ? 'Data' : 'Data da compra'}</label>
        <input
          id={`${id}-data`}
          type="date"
          required
          min="1900-01-01"
          max="2199-12-31"
          value={data}
          onChange={(evento) => setData(evento.target.value)}
        />
        {cartao !== null && /^\d{4}-\d{2}-\d{2}$/.test(data) && (
          <small>
            Entra na fatura que vence em{' '}
            {formatarDiaMes(vencimentoDaFatura(data, cartao.diaFechamento, cartao.diaVencimento))}
          </small>
        )}
      </div>
      {mensagem !== null && (
        <p id={`${id}-erro`} role="alert" className="erro">
          {mensagem}
        </p>
      )}
      <button type="submit" disabled={enviando}>
        {enviando ? 'Confirmando...' : 'Confirmar'}
      </button>
      <button type="button" disabled={enviando} onClick={aoVoltar}>
        Voltar
      </button>
    </form>
  )
}

function FormularioDeTransferencia({
  contas,
  aoSalvar,
  aoSessaoExpirar,
}: {
  contas: Conta[]
  aoSalvar: () => void
  aoSessaoExpirar: () => void
}) {
  const [origemEscolhida, setOrigemEscolhida] = useState(contas[0]?.id ?? '')
  const [destinoEscolhido, setDestinoEscolhido] = useState(contas[1]?.id ?? '')
  const [titulo, setTitulo] = useState('')
  const [valor, setValor] = useState('')
  const [data, setData] = useState(hojeLocal())
  const [descricao, setDescricao] = useState('')
  const [enviando, setEnviando] = useState(false)
  const [mensagem, setMensagem] = useState<string | null>(null)
  // O state só atualiza no próximo render; o ref barra o segundo submit síncrono.
  const emVoo = useRef(false)

  // A conta escolhida pode ter sido arquivada e sumido da lista; cai na primeira disponível.
  const contaOrigemId = contas.some((c) => c.id === origemEscolhida) ? origemEscolhida : (contas[0]?.id ?? '')
  const contaDestinoId = contas.some((c) => c.id === destinoEscolhido)
    ? destinoEscolhido
    : (contas.find((c) => c.id !== contaOrigemId)?.id ?? '')

  if (contas.length < 2) {
    return <p>Crie duas contas para transferir.</p>
  }

  async function enviar(evento: FormEvent<HTMLFormElement>): Promise<void> {
    evento.preventDefault()
    if (emVoo.current) return
    if (contaOrigemId === contaDestinoId) {
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
        contaOrigemId,
        contaDestinoId,
        valorCentavos,
        data,
        // Título ausente faz o servidor usar "Transferência".
        ...(titulo.trim() === '' ? {} : { titulo: titulo.trim() }),
        ...(descricao.trim() === '' ? {} : { descricao: descricao.trim() }),
      })
      setTitulo('')
      setValor('')
      setDescricao('')
      aoSalvar()
    } catch (erro) {
      if (erro instanceof SessaoExpiradaError) {
        aoSessaoExpirar()
        return
      }
      setMensagem(mensagemDeTransferencia(erro))
    } finally {
      emVoo.current = false
      setEnviando(false)
    }
  }

  const descricaoDoErro = mensagem === null ? undefined : 'erro-transferencia'

  return (
    <form onSubmit={(evento) => void enviar(evento)} aria-label="Nova transferência">
      <h3>Nova transferência</h3>
      <div className="campo">
        <label htmlFor="transferencia-origem">Conta de origem</label>
        <select
          id="transferencia-origem"
          value={contaOrigemId}
          required
          onChange={(evento) => setOrigemEscolhida(evento.target.value)}
        >
          {contas.map((conta) => (
            <option key={conta.id} value={conta.id}>
              {conta.nome}
            </option>
          ))}
        </select>
      </div>
      <div className="campo">
        <label htmlFor="transferencia-destino">Conta de destino</label>
        <select
          id="transferencia-destino"
          value={contaDestinoId}
          required
          onChange={(evento) => setDestinoEscolhido(evento.target.value)}
        >
          {contas.map((conta) => (
            <option key={conta.id} value={conta.id}>
              {conta.nome}
            </option>
          ))}
        </select>
      </div>
      <div className="campo">
        <label htmlFor="transferencia-valor">Valor em reais</label>
        <input
          id="transferencia-valor"
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
        <label htmlFor="transferencia-data">Data</label>
        <input
          id="transferencia-data"
          type="date"
          required
          min="1900-01-01"
          max="2199-12-31"
          value={data}
          onChange={(evento) => setData(evento.target.value)}
        />
      </div>
      <div className="campo">
        <label htmlFor="transferencia-titulo">Título (opcional)</label>
        <input
          id="transferencia-titulo"
          type="text"
          maxLength={100}
          placeholder="Transferência"
          value={titulo}
          onChange={(evento) => setTitulo(evento.target.value)}
        />
      </div>
      <div className="campo">
        <label htmlFor="transferencia-descricao">Descrição (opcional)</label>
        <input
          id="transferencia-descricao"
          type="text"
          maxLength={500}
          value={descricao}
          onChange={(evento) => setDescricao(evento.target.value)}
        />
      </div>
      {mensagem !== null && (
        <p id="erro-transferencia" role="alert" className="erro">
          {mensagem}
        </p>
      )}
      <button type="submit" disabled={enviando}>
        {enviando ? 'Salvando...' : 'Adicionar transferência'}
      </button>
    </form>
  )
}

export default function Lancamentos({
  aoSessaoExpirar,
  contas,
  aoMudarSaldo,
  recorrenciasAtivas,
  parcelasPorRecorrencia,
  versaoDaLista,
  aoMudarOcorrencia,
}: {
  aoSessaoExpirar: () => void
  // Contas ativas, vindas da tela de Contas; null até a primeira carga.
  contas: Conta[] | null
  // Avisa a lista de contas que o saldo mudou.
  aoMudarSaldo: () => void
  // Ids das recorrências ativas, para avisar que apagar uma ocorrência futura a faz voltar.
  recorrenciasAtivas: ReadonlySet<string>
  // Quantas parcelas cada recorrência com divisão tem, para escrever "parcela 1 de 2".
  parcelasPorRecorrencia: ReadonlyMap<string, number>
  // Sobe quando outra tela mexeu nas ocorrências (criar ou desativar recorrência).
  versaoDaLista: number
  // Avisa a tela de recorrências que a próxima ocorrência pode ter mudado.
  aoMudarOcorrencia: () => void
}) {
  const [mes, setMes] = useState<Mes>(() => mesDeHoje())
  const [filtroDeConta, setFiltroDeConta] = useState('')
  const [estado, setEstado] = useState<EstadoLista>({ tipo: 'carregando' })
  const [apoio, setApoio] = useState<EstadoCategorias>({ tipo: 'carregando' })
  const [tentativa, setTentativa] = useState(0)
  const [tentativaDoApoio, setTentativaDoApoio] = useState(0)
  const [vencidas, setVencidas] = useState<EstadoVencidas>({ tipo: 'carregando' })
  const [tentativaDeVencidas, setTentativaDeVencidas] = useState(0)
  const [editando, setEditando] = useState<Lancamento | null>(null)
  // Dois cliques para apagar e para cancelar; a ação guardada evita confirmar a errada.
  // "ajustar" abre o mini formulário de confirmar. O lugar separa a mesma prevista na lista do mês e em Vencidas.
  const [confirmando, setConfirmando] = useState<{
    id: string
    acao: 'apagar' | 'cancelar' | 'ajustar'
    lugar: 'mes' | 'vencidas'
  } | null>(null)
  const [apagando, setApagando] = useState<string | null>(null)
  const [falhaDeAcao, setFalhaDeAcao] = useState<string | null>(null)
  // Muda a key do formulário de criação para limpar os campos depois de salvar.
  const [versaoDoFormulario, setVersaoDoFormulario] = useState(0)
  const [modoDoFormulario, setModoDoFormulario] = useState<'lancamento' | 'transferencia'>('lancamento')
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
  }, [mes, filtroDeConta, tentativa, versaoDaLista, aoSessaoExpirar])

  useEffect(() => {
    let ativo = true
    // Não volta para "carregando" a cada recarga: o bloco não pisca enquanto a nova lista chega.
    fetchVencidos().then(
      (resposta) => {
        if (ativo) setVencidas({ tipo: 'ok', ...resposta })
      },
      (erro: unknown) => {
        if (!ativo) return
        if (erro instanceof SessaoExpiradaError) {
          aoSessaoExpirar()
          return
        }
        setVencidas({ tipo: 'erro', mensagem: mensagemDeLeitura(erro, 'as vencidas') })
      },
    )
    return () => {
      ativo = false
    }
  }, [tentativaDeVencidas, versaoDaLista, aoSessaoExpirar])

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
    setTentativaDeVencidas((n) => n + 1)
    aoMudarSaldo()
    aoMudarOcorrencia()
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
      // Apagar uma perna apaga o par inteiro; /lancamentos recusaria com 400.
      if (lancamento.transferenciaId === null) await deleteLancamento(lancamento.id)
      else await deleteTransferencia(lancamento.transferenciaId)
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
      if (erro instanceof LancamentoNaoEncontradoError || erro instanceof TransferenciaNaoEncontradaError) {
        depoisDeMudar()
      }
    } finally {
      acaoEmVoo.current = false
      setApagando(null)
    }
  }

  async function confirmar(lancamento: Lancamento, ajuste: AjusteDeConfirmacao): Promise<string | null> {
    if (acaoEmVoo.current) return null
    acaoEmVoo.current = true
    setApagando(lancamento.id)
    setFalhaDeAcao(null)
    try {
      await patchStatusLancamento(lancamento.id, 'realizado', ajuste)
      setConfirmando(null)
      if (editando?.id === lancamento.id) setEditando(null)
      depoisDeMudar()
      return null
    } catch (erro) {
      if (erro instanceof SessaoExpiradaError) {
        aoSessaoExpirar()
        return null
      }
      if (erro instanceof LancamentoNaoEncontradoError) {
        // Sem o item não há o que ajustar: fecha o formulário e busca o que o servidor tem.
        setConfirmando(null)
        setFalhaDeAcao(MENSAGEM_NAO_EXISTE)
        depoisDeMudar()
        return null
      }
      // O formulário continua aberto para o usuário corrigir valor ou data.
      return mensagemDeStatus(erro)
    } finally {
      acaoEmVoo.current = false
      setApagando(null)
    }
  }

  async function cancelarOcorrencia(lancamento: Lancamento): Promise<void> {
    if (acaoEmVoo.current) return
    acaoEmVoo.current = true
    setApagando(lancamento.id)
    setFalhaDeAcao(null)
    try {
      await patchStatusLancamento(lancamento.id, 'cancelado')
      setConfirmando(null)
      if (editando?.id === lancamento.id) setEditando(null)
      depoisDeMudar()
    } catch (erro) {
      if (erro instanceof SessaoExpiradaError) {
        aoSessaoExpirar()
        return
      }
      setFalhaDeAcao(mensagemDeStatus(erro))
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
  const cartaoDaConta = (id: string): Conta['cartao'] => contas?.find((c) => c.id === id)?.cartao ?? null
  if (apoio.tipo === 'ok') {
    apoio.categorias.forEach((categoria) => nomesDeCategoria.set(categoria.id, categoria.nome))
  }

  // BigInt porque a soma de muitos lançamentos pode passar do inteiro seguro.
  let receitas = 0n
  let despesas = 0n
  let previstoReceitas = 0n
  let previstoDespesas = 0n
  const hoje = hojeLocal()
  if (estado.tipo === 'ok') {
    for (const lancamento of estado.lancamentos) {
      // Previsto e cancelado não são fato consumado: os totais seguem o saldo, só realizado.
      if (lancamento.status === 'previsto') {
        if (lancamento.tipo === 'receita') previstoReceitas += BigInt(lancamento.valorCentavos)
        else if (lancamento.tipo === 'despesa') previstoDespesas += BigInt(lancamento.valorCentavos)
        continue
      }
      if (lancamento.status !== 'realizado') continue
      if (lancamento.tipo === 'receita') receitas += BigInt(lancamento.valorCentavos)
      else if (lancamento.tipo === 'despesa') despesas += BigInt(lancamento.valorCentavos)
      // Transferência fica fora: mover dinheiro entre contas não é ganho nem gasto.
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
      {/* Sem as contas a lista mostraria "Conta arquivada" para todo mundo por um instante. */}
      {(estado.tipo === 'carregando' || (estado.tipo === 'ok' && contas === null)) && (
        <p>Carregando lançamentos...</p>
      )}
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
      {vencidas.tipo === 'erro' && (
        <div className="vencidas-aviso">
          <p role="alert" className="erro">
            {vencidas.mensagem}
          </p>
          <button
            type="button"
            aria-label="Tentar carregar as vencidas de novo"
            onClick={() => setTentativaDeVencidas((n) => n + 1)}
          >
            Tentar de novo
          </button>
        </div>
      )}
      {vencidas.tipo === 'ok' && vencidas.lancamentos.length > 0 && contas !== null && (
        <section className="vencidas" aria-labelledby="titulo-vencidas">
          <h3 id="titulo-vencidas">Vencidas ({vencidas.lancamentos.length})</h3>
          {vencidas.maisAlgum && <p>mostrando as 200 mais antigas</p>}
          <ul className="lancamentos">
            {vencidas.lancamentos.map((vencida) => {
              const sinal = vencida.tipo === 'receita' ? '+' : '−'
              const confirmandoEsta =
                confirmando?.id === vencida.id && confirmando.lugar === 'vencidas' ? confirmando.acao : null
              return (
                <li key={vencida.id} className="previsto">
                  <div className="lancamento-dados">
                    <span>{formatarDataCompleta(vencida.dataCaixa)}</span>
                    <strong>{vencida.titulo}</strong>
                    <span>{nomesDeConta.get(vencida.contaId) ?? 'Conta arquivada'}</span>
                    <span className={vencida.tipo}>
                      {sinal} {formatarCentavos(vencida.valorCentavos)}
                    </span>
                  </div>
                  <div className="lancamento-acoes">
                    {confirmandoEsta !== 'ajustar' && (
                      <button
                        type="button"
                        disabled={apagando !== null}
                        aria-label={`Confirmar ${vencida.titulo}`}
                        onClick={() => {
                          setFalhaDeAcao(null)
                          setConfirmando({ id: vencida.id, acao: 'ajustar', lugar: 'vencidas' })
                        }}
                      >
                        Confirmar
                      </button>
                    )}
                    <button
                      type="button"
                      disabled={apagando !== null}
                      aria-label={`Editar ${vencida.titulo}`}
                      onClick={() => {
                        setFalhaDeAcao(null)
                        setConfirmando(null)
                        setEditando(vencida)
                      }}
                    >
                      Editar
                    </button>
                    {confirmandoEsta === 'cancelar' ? (
                      <>
                        <button
                          type="button"
                          disabled={apagando !== null}
                          aria-label={`Confirmar cancelar ${vencida.titulo}`}
                          onClick={() => void cancelarOcorrencia(vencida)}
                        >
                          Confirmar cancelar
                        </button>
                        <button type="button" disabled={apagando !== null} onClick={() => setConfirmando(null)}>
                          Manter
                        </button>
                      </>
                    ) : (
                      <button
                        type="button"
                        disabled={apagando !== null}
                        aria-label={`Cancelar ${vencida.titulo}`}
                        onClick={() => setConfirmando({ id: vencida.id, acao: 'cancelar', lugar: 'vencidas' })}
                      >
                        Cancelar
                      </button>
                    )}
                  </div>
                  {confirmandoEsta === 'ajustar' && (
                    <FormularioDeConfirmacao
                      prevista={vencida}
                      cartao={cartaoDaConta(vencida.contaId)}
                      aoConfirmar={(ajuste) => confirmar(vencida, ajuste)}
                      aoVoltar={() => setConfirmando(null)}
                    />
                  )}
                </li>
              )
            })}
          </ul>
        </section>
      )}
      {estado.tipo === 'ok' && contas !== null && (
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
          <p className="previsto-no-mes">
            Previsto no mês: <span className="receita">+{formatarCentavos(previstoReceitas)}</span>
            {' / '}
            <span className="despesa">−{formatarCentavos(previstoDespesas)}</span>
          </p>
          {estado.lancamentos.length === 0 && <p>Nenhum lançamento neste mês.</p>}
          {estado.lancamentos.length > 0 && (
            <ul className="lancamentos">
              {estado.lancamentos.map((lancamento) => {
                const ePerna = lancamento.tipo === 'transferencia'
                const sinal =
                  lancamento.tipo === 'receita' || lancamento.direcao === 'entrada' ? '+' : '−'
                const ePrevista = lancamento.status === 'previsto'
                const eCancelada = lancamento.status === 'cancelado'
                // Só vale o aviso se a série ainda gera e a data cai dentro do que ela repõe.
                const voltaComoPrevista =
                  lancamento.templateId !== null &&
                  recorrenciasAtivas.has(lancamento.templateId) &&
                  lancamento.dataCaixa >= hoje
                const confirmandoEste =
                  confirmando?.id === lancamento.id && confirmando.lugar === 'mes' ? confirmando.acao : null
                return (
                  <li
                    key={lancamento.id}
                    className={ePrevista ? 'previsto' : eCancelada ? 'cancelado' : undefined}
                  >
                    <div className="lancamento-dados">
                      <span>{formatarDiaMes(lancamento.dataCaixa)}</span>
                      <strong>{lancamento.titulo}</strong>
                      {/* Na fatura do cartão o caixa cai depois da compra; mostra as duas. */}
                      {lancamento.dataCompetencia !== lancamento.dataCaixa && (
                        <small>compra em {formatarDiaMes(lancamento.dataCompetencia)}</small>
                      )}
                      <span>
                        {ePerna
                          ? 'Transferência'
                          : ((lancamento.categoriaId !== null && nomesDeCategoria.get(lancamento.categoriaId)) ||
                            'Sem categoria')}
                        {' · '}
                        {nomesDeConta.get(lancamento.contaId) ?? 'Conta arquivada'}
                      </span>
                      {lancamento.parcelaOrdem !== null && (
                        <small>
                          {lancamento.templateId !== null && parcelasPorRecorrencia.has(lancamento.templateId)
                            ? `parcela ${lancamento.parcelaOrdem} de ${parcelasPorRecorrencia.get(lancamento.templateId)}`
                            : `parcela ${lancamento.parcelaOrdem}`}
                        </small>
                      )}
                      {(ePrevista || eCancelada) && (
                        <span className="marca">{ePrevista ? 'Previsto' : 'Cancelado'}</span>
                      )}
                      <span className={lancamento.tipo}>
                        {sinal} {formatarCentavos(lancamento.valorCentavos)}
                      </span>
                    </div>
                    <div className="lancamento-acoes">
                      {ePrevista && confirmandoEste !== 'ajustar' && (
                        <button
                          type="button"
                          disabled={apagando !== null}
                          aria-label={`Confirmar ${lancamento.titulo}`}
                          onClick={() => {
                            setFalhaDeAcao(null)
                            setConfirmando({ id: lancamento.id, acao: 'ajustar', lugar: 'mes' })
                          }}
                        >
                          Confirmar
                        </button>
                      )}
                      {!ePerna && !eCancelada && (
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
                      )}
                      {ePrevista &&
                        (confirmandoEste === 'cancelar' ? (
                          <>
                            <button
                              type="button"
                              disabled={apagando !== null}
                              aria-label={`Confirmar cancelar ${lancamento.titulo}`}
                              onClick={() => void cancelarOcorrencia(lancamento)}
                            >
                              Confirmar cancelar
                            </button>
                            <button type="button" disabled={apagando !== null} onClick={() => setConfirmando(null)}>
                              Manter
                            </button>
                          </>
                        ) : (
                          <button
                            type="button"
                            disabled={apagando !== null}
                            aria-label={`Cancelar ${lancamento.titulo}`}
                            onClick={() => setConfirmando({ id: lancamento.id, acao: 'cancelar', lugar: 'mes' })}
                          >
                            Cancelar
                          </button>
                        ))}
                      {/* Apagar prevista é recusado pela API: o caminho é cancelar. */}
                      {!ePrevista &&
                        (confirmandoEste === 'apagar' ? (
                          <>
                            {ePerna && <span>Apaga as duas pernas da transferência.</span>}
                            {voltaComoPrevista && <span>Essa ocorrência volta como prevista.</span>}
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
                            onClick={() => setConfirmando({ id: lancamento.id, acao: 'apagar', lugar: 'mes' })}
                          >
                            Apagar
                          </button>
                        ))}
                    </div>
                    {confirmandoEste === 'ajustar' && (
                      <FormularioDeConfirmacao
                        prevista={lancamento}
                        cartao={cartaoDaConta(lancamento.contaId)}
                        aoConfirmar={(ajuste) => confirmar(lancamento, ajuste)}
                        aoVoltar={() => setConfirmando(null)}
                      />
                    )}
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
      {contas !== null && editando === null && (
        <div role="group" aria-label="Tipo de registro">
          <button
            type="button"
            aria-pressed={modoDoFormulario === 'lancamento'}
            onClick={() => setModoDoFormulario('lancamento')}
          >
            Lançamento
          </button>
          <button
            type="button"
            aria-pressed={modoDoFormulario === 'transferencia'}
            onClick={() => setModoDoFormulario('transferencia')}
          >
            Transferência
          </button>
        </div>
      )}
      {contas !== null && editando === null && modoDoFormulario === 'transferencia' && (
        <FormularioDeTransferencia
          key={`transferencia-${versaoDoFormulario}`}
          contas={contas}
          aoSalvar={() => {
            setVersaoDoFormulario((n) => n + 1)
            depoisDeMudar()
          }}
          aoSessaoExpirar={aoSessaoExpirar}
        />
      )}
      {(editando !== null || modoDoFormulario === 'lancamento') && apoio.tipo === 'ok' && contas !== null && (
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
