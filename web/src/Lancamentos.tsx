import { useEffect, useRef, useState } from 'react'
import type { FormEvent, ReactNode } from 'react'
import { createPortal } from 'react-dom'
import {
  CompraParceladaInvalidaError,
  CompraParceladaNaoEncontradaError,
  LancamentoInvalidoError,
  LancamentoNaoEncontradoError,
  ServicoIndisponivelError,
  SessaoExpiradaError,
  TransferenciaInvalidaError,
  TransferenciaNaoEncontradaError,
  deleteCompraParcelada,
  deleteLancamento,
  deleteTransferencia,
  fetchCategorias,
  fetchCompraParcelada,
  fetchLancamentos,
  fetchVencidos,
  patchCompraParcelada,
  patchStatusLancamento,
  fetchTerceiros,
  ligarCompraAoTerceiro,
  ligarLancamentoAoTerceiro,
  postCompraParcelada,
  postLancamento,
  postTransferencia,
  putLancamento,
} from './api'
import type { AjusteDeConfirmacao, Categoria, CompraParceladaAtualizarRequest, Conta, Lancamento, Terceiro } from './api'
import { categoriaEfetiva, categoriasDaConta } from './categoriasDaConta'
import { vencimentoDaFatura } from './fatura'
import {
  formatarDataCompleta,
  formatarDiaMes,
  hojeLocal,
  intervaloDoMes,
  mesDeHoje,
  rotuloDoDia,
  rotuloDoMes,
  somarMeses,
} from './datas'
import type { Mes } from './datas'
import { formatarCentavos, lerValorPositivo } from './dinheiro'
import {
  IconeMais,
  IconeSetaBaixo,
  IconeSetaCima,
  IconeSetaDireita,
  IconeSetaEsquerda,
  IconeTroca,
} from './icones'
import MenuDeAcoes from './MenuDeAcoes'
import Painel from './Painel'
import { calcularParcelas } from './parcelas'
import { aindaNaoAconteceu, somarTotaisDoMes } from './totaisDoMes'
import { Carregando, EstadoVazio, Selo } from './ui'

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
const MENSAGEM_PARCELA_ZERO = 'Valor pequeno demais: cada parcela precisa ter ao menos 1 centavo.'
const MENSAGEM_COMPRA_NAO_EXISTE = 'Essa compra não existe mais.'
const AVISO_DE_PARCELA = 'Para mudar conta ou data, apague a compra e lance de novo.'
const NUMEROS_DE_PARCELAS = Array.from({ length: 47 }, (_, i) => i + 2)
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
  if (erro instanceof CompraParceladaInvalidaError && erro.mensagemServidor !== null) {
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

function mensagemDeCompra(erro: unknown, verbo: 'alterar' | 'apagar'): string {
  if (erro instanceof CompraParceladaNaoEncontradaError) return MENSAGEM_COMPRA_NAO_EXISTE
  if (erro instanceof CompraParceladaInvalidaError && erro.mensagemServidor !== null) {
    return erro.mensagemServidor
  }
  if (erro instanceof ServicoIndisponivelError) return MENSAGEM_INDISPONIVEL
  return `Não foi possível ${verbo} a compra. Tente de novo.`
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

// "3x de R$ 333,33 (última R$ 333,34), de 10/11 a 10/01": a última só aparece quando leva o resto.
function textoDaPrevia(parcelas: { valorCentavos: number; vencimento: string }[]): string {
  const primeira = parcelas[0]
  const ultima = parcelas[parcelas.length - 1]
  const dividido = `${parcelas.length}x de ${formatarCentavos(primeira.valorCentavos)}`
  const resto = ultima.valorCentavos === primeira.valorCentavos ? '' : ` (última ${formatarCentavos(ultima.valorCentavos)})`
  return `${dividido}${resto}, de ${formatarDiaMes(primeira.vencimento)} a ${formatarDiaMes(ultima.vencimento)}`
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
  // Vazio é à vista; o resto é o número de parcelas, de 2 a 48.
  const [parcelas, setParcelas] = useState('')
  // Terceiro de quem é a compra ou o reembolso (ADR-062). Vazio é do próprio dono.
  const [terceiroEscolhido, setTerceiroEscolhido] = useState(inicial?.terceiroId ?? '')
  const [terceiros, setTerceiros] = useState<Terceiro[]>([])
  const [enviando, setEnviando] = useState(false)
  const [mensagem, setMensagem] = useState<string | null>(null)
  // O state só atualiza no próximo render; o ref barra o segundo submit síncrono.
  const emVoo = useRef(false)

  useEffect(() => {
    let vale = true
    // Sem a lista o campo fica sem opção e o lançamento sai do dono, como antes.
    fetchTerceiros().then(
      (lista) => {
        if (vale) setTerceiros(lista.filter((t) => !t.arquivado || t.id === inicial?.terceiroId))
      },
      (erro: unknown) => {
        if (erro instanceof SessaoExpiradaError) aoSessaoExpirar()
      },
    )
    return () => {
      vale = false
    }
  }, [aoSessaoExpirar, inicial?.terceiroId])

  // Conta e data de uma parcela só mudam apagando a compra (ADR-032); o servidor recusa com 400.
  const eParcela = inicial !== null && inicial.compraParceladaId !== null
  const contaId = contas.some((c) => c.id === contaEscolhida)
    ? contaEscolhida
    : // Lançamento numa conta arquivada continua editável sem trocar de conta.
      inicial !== null && contaEscolhida === inicial.contaId
      ? contaEscolhida
      : (contas[0]?.id ?? '')
  const contaSelecionada = contas.find((c) => c.id === contaId)
  const cartaoEscolhido = contaSelecionada?.cartao ?? null
  const { categorias: categoriasDoTipo, restrita: categoriasRestritas } = categoriasDaConta(
    categorias,
    tipo,
    contaSelecionada,
  )
  // Ao trocar o tipo a escolha antiga some da lista; cai na primeira em vez de guardar id inválido.
  const categoriaId = categoriaEfetiva(categoriaEscolhida, categoriasDoTipo, categoriasRestritas)
  const categoriaForaDaConta = categoriasRestritas && categoriaEscolhida !== '' && categoriaId === ''
  const contaArquivadaDoLancamento =
    inicial !== null && !contas.some((c) => c.id === inicial.contaId) ? inicial.contaId : null

  if (contas.length === 0 && contaArquivadaDoLancamento === null) {
    return <p>Crie uma conta antes de lançar.</p>
  }

  // Parcelar vale só para despesa nova em cartão; trocar conta ou tipo desfaz o parcelamento sem estado extra.
  const cartaoParcelavel = inicial === null && tipo === 'despesa' ? cartaoEscolhido : null
  const quantidadeDeParcelas = Number(parcelas)
  const parcelando = cartaoParcelavel !== null && quantidadeDeParcelas >= 2
  const totalParaPrevia = parcelando ? lerValorPositivo(valor) : null
  const previa =
    cartaoParcelavel !== null && totalParaPrevia !== null && /^\d{4}-\d{2}-\d{2}$/.test(data)
      ? calcularParcelas(totalParaPrevia, quantidadeDeParcelas, data, cartaoParcelavel)
      : null

  async function enviar(evento: FormEvent<HTMLFormElement>): Promise<void> {
    evento.preventDefault()
    if (emVoo.current) return
    const valorCentavos = lerValorPositivo(valor)
    if (valorCentavos === null) {
      setMensagem(MENSAGEM_VALOR_INVALIDO)
      return
    }
    if (cartaoParcelavel !== null && parcelando) {
      if (calcularParcelas(valorCentavos, quantidadeDeParcelas, data, cartaoParcelavel).tipo === 'parcela-zero') {
        setMensagem(MENSAGEM_PARCELA_ZERO)
        return
      }
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
        if (parcelando) {
          const compra = await postCompraParcelada({
            titulo: corpo.titulo,
            ...(descricao.trim() === '' ? {} : { descricao: descricao.trim() }),
            valorTotalCentavos: valorCentavos,
            parcelas: quantidadeDeParcelas,
            categoriaId,
            contaId,
            data,
          })
          if (terceiroEscolhido !== '') await ligarCompraAoTerceiro(compra.id, terceiroEscolhido)
        } else {
          const criado = await postLancamento(corpo)
          if (terceiroEscolhido !== '') await ligarLancamentoAoTerceiro(criado.id, terceiroEscolhido)
        }
        setTerceiroEscolhido('')
        setParcelas('')
        setTitulo('')
        setValor('')
        setDescricao('')
      } else {
        await putLancamento(inicial.id, corpo)
        if (terceiroEscolhido !== (inicial.terceiroId ?? '')) {
          await ligarLancamentoAoTerceiro(inicial.id, terceiroEscolhido === '' ? null : terceiroEscolhido)
        }
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
  const nome = inicial === null ? 'Novo lançamento' : eParcela ? 'Editar parcela' : 'Editar lançamento'
  const avisoDaParcela = eParcela ? 'lancamento-aviso-parcela' : undefined

  return (
    <form onSubmit={(evento) => void enviar(evento)} aria-label={nome}>
      <div className="campo">
        <label htmlFor="lancamento-tipo">Tipo</label>
        <select
          id="lancamento-tipo"
          value={tipo}
          disabled={eParcela}
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
        <label htmlFor="lancamento-valor">
          {parcelando ? 'Valor total em reais' : eParcela ? 'Valor em reais (desta parcela)' : 'Valor em reais'}
        </label>
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
      {terceiros.length > 0 && (
        <div className="campo">
          <label htmlFor="lancamento-terceiro">Terceiro (opcional)</label>
          <select
            id="lancamento-terceiro"
            value={terceiroEscolhido}
            onChange={(evento) => setTerceiroEscolhido(evento.target.value)}
          >
            <option value="">Meu</option>
            {terceiros.map((terceiro) => (
              <option key={terceiro.id} value={terceiro.id}>
                {terceiro.nome}
              </option>
            ))}
          </select>
        </div>
      )}
      <div className="campo">
        <label htmlFor="lancamento-categoria">Categoria</label>
        <select
          id="lancamento-categoria"
          value={categoriaId}
          required
          aria-describedby={categoriasRestritas ? 'lancamento-aviso-categoria' : undefined}
          onChange={(evento) => setCategoriaEscolhida(evento.target.value)}
        >
          {categoriaId === '' && (
            <option value="" disabled>
              Escolha uma categoria
            </option>
          )}
          {categoriasDoTipo.map((categoria) => (
            <option key={categoria.id} value={categoria.id}>
              {categoria.nome}
            </option>
          ))}
        </select>
        {categoriasRestritas && (
          <small id="lancamento-aviso-categoria" aria-live="polite">
            {categoriaForaDaConta
              ? 'A categoria escolhida não vale nesta conta. Escolha outra.'
              : 'Esta conta só aceita algumas categorias de despesa.'}
          </small>
        )}
      </div>
      <div className="campo">
        <label htmlFor="lancamento-conta">Conta</label>
        <select
          id="lancamento-conta"
          value={contaId}
          required
          disabled={eParcela}
          aria-describedby={avisoDaParcela}
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
          disabled={eParcela}
          aria-describedby={avisoDaParcela}
          min="1900-01-01"
          max="2199-12-31"
          value={data}
          onChange={(evento) => setData(evento.target.value)}
        />
        {cartaoEscolhido !== null && !eParcela && /^\d{4}-\d{2}-\d{2}$/.test(data) && (
          <small>
            Entra na fatura que vence em{' '}
            {formatarDiaMes(vencimentoDaFatura(data, cartaoEscolhido.diaFechamento, cartaoEscolhido.diaVencimento))}
          </small>
        )}
        {eParcela && <small id="lancamento-aviso-parcela">{AVISO_DE_PARCELA}</small>}
      </div>
      {cartaoParcelavel !== null && (
        <div className="campo">
          <label htmlFor="lancamento-parcelas">Parcelar</label>
          <select
            id="lancamento-parcelas"
            value={parcelas}
            onChange={(evento) => setParcelas(evento.target.value)}
          >
            <option value="">À vista</option>
            {NUMEROS_DE_PARCELAS.map((n) => (
              <option key={n} value={String(n)}>
                {n}x
              </option>
            ))}
          </select>
          {previa?.tipo === 'ok' && <small aria-live="polite">{textoDaPrevia(previa.parcelas)}</small>}
          {previa?.tipo === 'parcela-zero' && (
            <small aria-live="polite" className="erro">
              {MENSAGEM_PARCELA_ZERO}
            </small>
          )}
        </div>
      )}
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
        <button type="button" className="discreto" onClick={aoCancelar}>
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
      <button type="button" className="discreto" disabled={enviando} onClick={aoVoltar}>
        Voltar
      </button>
    </form>
  )
}

function FormularioDeCompra({
  parcela,
  categorias,
  aoSalvar,
  aoVoltar,
}: {
  parcela: Lancamento
  categorias: Categoria[]
  // Devolve a mensagem de erro, ou null quando deu certo e o formulário vai fechar.
  aoSalvar: (dados: CompraParceladaAtualizarRequest) => Promise<string | null>
  aoVoltar: () => void
}) {
  const [titulo, setTitulo] = useState(parcela.titulo)
  const [descricao, setDescricao] = useState(parcela.descricao ?? '')
  const [categoriaId, setCategoriaId] = useState(parcela.categoriaId ?? '')
  const [enviando, setEnviando] = useState(false)
  const [mensagem, setMensagem] = useState<string | null>(null)
  // O state só atualiza no próximo render; o ref barra o segundo submit síncrono.
  const emVoo = useRef(false)

  async function enviar(evento: FormEvent<HTMLFormElement>): Promise<void> {
    evento.preventDefault()
    if (emVoo.current) return
    // Só o que mudou vai no PATCH: a parcela pode ter sido editada à parte e não deve ditar o título das outras.
    const dados: CompraParceladaAtualizarRequest = {
      ...(titulo.trim() === parcela.titulo ? {} : { titulo: titulo.trim() }),
      // Descrição em branco limpa no servidor, então a string vazia é um valor válido.
      ...(descricao.trim() === (parcela.descricao ?? '') ? {} : { descricao: descricao.trim() }),
      ...(categoriaId === parcela.categoriaId ? {} : { categoriaId }),
    }
    if (Object.keys(dados).length === 0) {
      setMensagem('Nada mudou. Altere título, descrição ou categoria.')
      return
    }
    emVoo.current = true
    setEnviando(true)
    setMensagem(null)
    try {
      setMensagem(await aoSalvar(dados))
    } finally {
      emVoo.current = false
      setEnviando(false)
    }
  }

  const id = `compra-${parcela.id}`
  const descricaoDoErro = mensagem === null ? undefined : `${id}-erro`
  const despesas = categorias.filter((categoria) => categoria.tipo === 'despesa')

  return (
    <form
      className="confirmacao"
      onSubmit={(evento) => void enviar(evento)}
      aria-label={`Editar compra ${parcela.titulo}`}
    >
      <div className="campo">
        <label htmlFor={`${id}-titulo`}>Título da compra</label>
        <input
          id={`${id}-titulo`}
          type="text"
          maxLength={100}
          required
          value={titulo}
          aria-describedby={descricaoDoErro}
          onChange={(evento) => setTitulo(evento.target.value)}
        />
      </div>
      <div className="campo">
        <label htmlFor={`${id}-descricao`}>Descrição da compra (opcional)</label>
        <input
          id={`${id}-descricao`}
          type="text"
          maxLength={500}
          value={descricao}
          onChange={(evento) => setDescricao(evento.target.value)}
        />
      </div>
      <div className="campo">
        <label htmlFor={`${id}-categoria`}>Categoria da compra</label>
        <select
          id={`${id}-categoria`}
          value={categoriaId}
          required
          onChange={(evento) => setCategoriaId(evento.target.value)}
        >
          {despesas.map((categoria) => (
            <option key={categoria.id} value={categoria.id}>
              {categoria.nome}
            </option>
          ))}
        </select>
      </div>
      <small>Muda todas as parcelas. Valor, parcelas, data e conta não mudam.</small>
      {mensagem !== null && (
        <p id={`${id}-erro`} role="alert" className="erro">
          {mensagem}
        </p>
      )}
      <button type="submit" disabled={enviando}>
        {enviando ? 'Salvando...' : 'Salvar compra'}
      </button>
      <button type="button" className="discreto" disabled={enviando} onClick={aoVoltar}>
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
  // Benefício não participa de transferência em nenhum dos lados (ADR-034).
  const destinos = contas.filter((c) => c.tipo !== 'beneficio')
  // O servidor recusa cartão como origem de transferência; ele só entra como destino (pagar fatura).
  const origens = destinos.filter((c) => c.tipo !== 'cartao_credito')
  const [origemEscolhida, setOrigemEscolhida] = useState(origens[0]?.id ?? '')
  const [destinoEscolhido, setDestinoEscolhido] = useState(
    destinos.find((c) => c.id !== origens[0]?.id)?.id ?? '',
  )
  const [titulo, setTitulo] = useState('')
  const [valor, setValor] = useState('')
  const [data, setData] = useState(hojeLocal())
  const [descricao, setDescricao] = useState('')
  const [enviando, setEnviando] = useState(false)
  const [mensagem, setMensagem] = useState<string | null>(null)
  // O state só atualiza no próximo render; o ref barra o segundo submit síncrono.
  const emVoo = useRef(false)

  // A conta escolhida pode ter sido arquivada e sumido da lista; cai na primeira disponível.
  const contaOrigemId = origens.some((c) => c.id === origemEscolhida) ? origemEscolhida : (origens[0]?.id ?? '')
  const contaDestinoId = destinos.some((c) => c.id === destinoEscolhido)
    ? destinoEscolhido
    : (destinos.find((c) => c.id !== contaOrigemId)?.id ?? '')

  if (origens.length === 0 || destinos.length < 2) {
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
      <div className="campo">
        <label htmlFor="transferencia-origem">Conta de origem</label>
        <select
          id="transferencia-origem"
          value={contaOrigemId}
          required
          onChange={(evento) => setOrigemEscolhida(evento.target.value)}
        >
          {origens.map((conta) => (
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
          {destinos.map((conta) => (
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
  destinoDasVencidas,
  aoEditarVencida,
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
  // Onde desenhar o bloco de vencidas (no Início). Sem destino o bloco não aparece.
  destinoDasVencidas: HTMLElement | null
  // Chamado ao editar uma vencida: o painel de edição fica na tela de Lançamentos.
  aoEditarVencida: () => void
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
    acao: 'apagar' | 'cancelar' | 'ajustar' | 'apagar-parcela' | 'apagar-compra' | 'editar-compra'
    lugar: 'mes' | 'vencidas'
    // Quantas parcelas a compra tem agora, lido do servidor antes de pedir a confirmação.
    quantidade?: number
  } | null>(null)
  const [apagando, setApagando] = useState<string | null>(null)
  const [falhaDeAcao, setFalhaDeAcao] = useState<string | null>(null)
  // Muda a key do formulário de criação para limpar os campos depois de salvar.
  const [versaoDoFormulario, setVersaoDoFormulario] = useState(0)
  const [modoDoFormulario, setModoDoFormulario] = useState<'lancamento' | 'transferencia'>('lancamento')
  const [painelAberto, setPainelAberto] = useState(false)
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

  // O total de parcelas da compra pode ter diminuído (parcela apagada à parte), então a contagem vem do servidor.
  async function pedirApagarCompra(lancamento: Lancamento, compraId: string): Promise<void> {
    if (acaoEmVoo.current) return
    acaoEmVoo.current = true
    setApagando(lancamento.id)
    setFalhaDeAcao(null)
    try {
      const compra = await fetchCompraParcelada(compraId)
      setConfirmando({ id: lancamento.id, acao: 'apagar-compra', lugar: 'mes', quantidade: compra.itens.length })
    } catch (erro) {
      if (erro instanceof SessaoExpiradaError) {
        aoSessaoExpirar()
        return
      }
      setFalhaDeAcao(mensagemDeCompra(erro, 'apagar'))
      if (erro instanceof CompraParceladaNaoEncontradaError) depoisDeMudar()
    } finally {
      acaoEmVoo.current = false
      setApagando(null)
    }
  }

  async function apagarCompra(lancamento: Lancamento, compraId: string): Promise<void> {
    if (acaoEmVoo.current) return
    acaoEmVoo.current = true
    setApagando(lancamento.id)
    setFalhaDeAcao(null)
    try {
      await deleteCompraParcelada(compraId)
      setConfirmando(null)
      if (editando?.compraParceladaId === compraId) setEditando(null)
      depoisDeMudar()
    } catch (erro) {
      if (erro instanceof SessaoExpiradaError) {
        aoSessaoExpirar()
        return
      }
      setFalhaDeAcao(mensagemDeCompra(erro, 'apagar'))
      setConfirmando(null)
      // A lista na tela está velha: busca de novo o que o servidor tem.
      if (erro instanceof CompraParceladaNaoEncontradaError) depoisDeMudar()
    } finally {
      acaoEmVoo.current = false
      setApagando(null)
    }
  }

  async function editarCompra(
    lancamento: Lancamento,
    compraId: string,
    dados: CompraParceladaAtualizarRequest,
  ): Promise<string | null> {
    if (acaoEmVoo.current) return null
    acaoEmVoo.current = true
    setApagando(lancamento.id)
    setFalhaDeAcao(null)
    try {
      await patchCompraParcelada(compraId, dados)
      setConfirmando(null)
      depoisDeMudar()
      return null
    } catch (erro) {
      if (erro instanceof SessaoExpiradaError) {
        aoSessaoExpirar()
        return null
      }
      if (erro instanceof CompraParceladaNaoEncontradaError) {
        setConfirmando(null)
        setFalhaDeAcao(MENSAGEM_COMPRA_NAO_EXISTE)
        depoisDeMudar()
        return null
      }
      // O formulário continua aberto para o usuário corrigir.
      return mensagemDeCompra(erro, 'alterar')
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

  // Confirmar antes da data é dizer que o dinheiro já se moveu: a data passa a ser hoje.
  async function confirmarAgendado(lancamento: Lancamento, tipo: 'receita' | 'despesa', categoriaId: string): Promise<void> {
    if (acaoEmVoo.current) return
    acaoEmVoo.current = true
    setApagando(lancamento.id)
    setFalhaDeAcao(null)
    try {
      await putLancamento(lancamento.id, {
        tipo,
        titulo: lancamento.titulo,
        descricao: lancamento.descricao,
        valorCentavos: lancamento.valorCentavos,
        categoriaId,
        contaId: lancamento.contaId,
        data: hojeLocal(),
      })
      if (editando?.id === lancamento.id) setEditando(null)
      depoisDeMudar()
    } catch (erro) {
      if (erro instanceof SessaoExpiradaError) {
        aoSessaoExpirar()
        return
      }
      if (erro instanceof LancamentoNaoEncontradoError) {
        setFalhaDeAcao(MENSAGEM_NAO_EXISTE)
        depoisDeMudar()
        return
      }
      setFalhaDeAcao(mensagemDeGravacao(erro))
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

  const hoje = hojeLocal()
  const { receitas, despesas, previstoReceitas, previstoDespesas } =
    estado.tipo === 'ok'
      ? somarTotaisDoMes(estado.lancamentos, hoje)
      : { receitas: 0n, despesas: 0n, previstoReceitas: 0n, previstoDespesas: 0n }

  const noPrimeiroMes = mes.ano <= ANO_MINIMO && mes.mes === 1
  const noUltimoMes = mes.ano >= ANO_MAXIMO && mes.mes === 12
  const rotulo = rotuloDoMes(mes)

  const semContas = contas !== null && contas.length === 0
  const painelVisivel = painelAberto || editando !== null
  const tituloDoPainel =
    editando !== null
      ? editando.compraParceladaId !== null
        ? 'Editar parcela'
        : 'Editar lançamento'
      : modoDoFormulario === 'transferencia'
        ? 'Nova transferência'
        : 'Novo lançamento'

  function fecharPainel(): void {
    setPainelAberto(false)
    setEditando(null)
  }

  // Lançamentos seguidos com a mesma data de caixa formam um grupo; a ordem é a que a API mandou.
  const grupos: { data: string; itens: Lancamento[] }[] = []
  if (estado.tipo === 'ok') {
    for (const lancamento of estado.lancamentos) {
      const ultimo = grupos[grupos.length - 1]
      if (ultimo !== undefined && ultimo.data === lancamento.dataCaixa) ultimo.itens.push(lancamento)
      else grupos.push({ data: lancamento.dataCaixa, itens: [lancamento] })
    }
  }

  const iconeDoItem = (lancamento: Lancamento): ReactNode =>
    lancamento.tipo === 'transferencia' ? (
      <IconeTroca tamanho={18} />
    ) : lancamento.tipo === 'receita' ? (
      <IconeSetaBaixo tamanho={18} />
    ) : (
      <IconeSetaCima tamanho={18} />
    )

  const blocoDeVencidas = (
    <>
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
        <section className="cartao vencidas" aria-labelledby="titulo-vencidas">
          <h3 id="titulo-vencidas" className="titulo-cartao">
            Vencidas ({vencidas.lancamentos.length})
          </h3>
          {vencidas.maisAlgum && <p>mostrando as 200 mais antigas</p>}
          <ul className="lancamentos">
            {vencidas.lancamentos.map((vencida) => {
              const sinal = vencida.tipo === 'receita' ? '+' : '−'
              const confirmandoEsta =
                confirmando?.id === vencida.id && confirmando.lugar === 'vencidas' ? confirmando.acao : null
              return (
                <li key={vencida.id} className="previsto">
                  <span className={`item-icone ${vencida.tipo}`}>{iconeDoItem(vencida)}</span>
                  <div className="item-corpo">
                    <strong className="item-titulo">{vencida.titulo}</strong>
                    <span className="item-meta">
                      {formatarDataCompleta(vencida.dataCaixa)}
                      {' · '}
                      {nomesDeConta.get(vencida.contaId) ?? 'Conta arquivada'}
                    </span>
                    <div className="item-selos">
                      <Selo tom="vencida">Vencida</Selo>
                    </div>
                  </div>
                  <div className="item-lado">
                    <span className={`item-valor ${vencida.tipo}`}>
                      {sinal} {formatarCentavos(vencida.valorCentavos)}
                    </span>
                    {confirmandoEsta !== 'cancelar' && (
                      <div className="item-botoes">
                        {confirmandoEsta !== 'ajustar' && (
                          <button
                            type="button"
                            className="pequeno destaque-acao"
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
                        <MenuDeAcoes rotulo={`Mais ações de ${vencida.titulo}`}>
                          <button
                            type="button"
                            disabled={apagando !== null}
                            aria-label={`Editar ${vencida.titulo}`}
                            onClick={() => {
                              setFalhaDeAcao(null)
                              setConfirmando(null)
                              setEditando(vencida)
                              aoEditarVencida()
                            }}
                          >
                            Editar
                          </button>
                          <button
                            type="button"
                            className="perigo"
                            disabled={apagando !== null}
                            aria-label={`Cancelar ${vencida.titulo}`}
                            onClick={() => setConfirmando({ id: vencida.id, acao: 'cancelar', lugar: 'vencidas' })}
                          >
                            Cancelar
                          </button>
                        </MenuDeAcoes>
                      </div>
                    )}
                  </div>
                  {confirmandoEsta === 'cancelar' && (
                    <div className="item-acoes">
                      <button
                        type="button"
                        className="pequeno perigo forte"
                        disabled={apagando !== null}
                        aria-label={`Confirmar cancelar ${vencida.titulo}`}
                        onClick={() => void cancelarOcorrencia(vencida)}
                      >
                        Confirmar cancelar
                      </button>
                      <button
                        type="button"
                        className="pequeno"
                        disabled={apagando !== null}
                        onClick={() => setConfirmando(null)}
                      >
                        Manter
                      </button>
                    </div>
                  )}
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
    </>
  )

  return (
    <section aria-labelledby="titulo-lancamentos">
      <header className="pagina-topo">
        <h2 id="titulo-lancamentos">Lançamentos</h2>
        <button
          type="button"
          className="primario botao-novo"
          disabled={contas === null || semContas}
          onClick={() => {
            setEditando(null)
            setPainelAberto(true)
          }}
        >
          <IconeMais />
          Novo
        </button>
      </header>
      {destinoDasVencidas !== null && createPortal(blocoDeVencidas, destinoDasVencidas)}
      <div className="mes-barra">
        <div className="mes-nav">
          <button type="button" aria-label="Mês anterior" disabled={noPrimeiroMes} onClick={() => navegar(-1)}>
            <IconeSetaEsquerda />
          </button>
          <strong aria-live="polite">{rotulo}</strong>
          <button type="button" aria-label="Próximo mês" disabled={noUltimoMes} onClick={() => navegar(1)}>
            <IconeSetaDireita />
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
      </div>
      {/* Sem as contas a lista mostraria "Conta arquivada" para todo mundo por um instante. */}
      {(estado.tipo === 'carregando' || (estado.tipo === 'ok' && contas === null)) && (
        <Carregando texto="Carregando lançamentos..." linhas={4} />
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
      {semContas && <p className="vazio-dica">Crie uma conta antes de lançar.</p>}
      {estado.tipo === 'ok' && contas !== null && (
        <>
          <dl className="pilulas">
            <div className="pilula">
              <dt>Receitas</dt>
              <dd className="receita">{formatarCentavos(receitas)}</dd>
              <dd className="pilula-previsto">Previsto {formatarCentavos(previstoReceitas)}</dd>
            </div>
            <div className="pilula">
              <dt>Despesas</dt>
              <dd className="despesa">{formatarCentavos(despesas)}</dd>
              <dd className="pilula-previsto">Previsto {formatarCentavos(previstoDespesas)}</dd>
            </div>
            <div className="pilula">
              <dt>Resultado</dt>
              <dd className={receitas - despesas < 0n ? 'despesa' : undefined}>{formatarCentavos(receitas - despesas)}</dd>
              <dd className="pilula-previsto">Previsto {formatarCentavos(previstoReceitas - previstoDespesas)}</dd>
            </div>
          </dl>
          {estado.lancamentos.length === 0 && (
            <EstadoVazio
              titulo="Nenhum lançamento neste mês."
              dica="Registre uma receita, uma despesa ou uma transferência."
              acao={
                semContas ? undefined : (
                  <button type="button" className="primario" onClick={() => setPainelAberto(true)}>
                    Adicionar o primeiro
                  </button>
                )
              }
            />
          )}
          {grupos.map((grupo) => (
            <div className="dia-grupo" key={grupo.itens[0].id}>
              <h3 className="dia-titulo">{rotuloDoDia(grupo.data, hoje)}</h3>
              <ul className="lancamentos lista-cartao">
                {grupo.itens.map((lancamento) => {
                  const ePerna = lancamento.tipo === 'transferencia'
                  const sinal = lancamento.tipo === 'receita' || lancamento.direcao === 'entrada' ? '+' : '−'
                  const ePrevista = lancamento.status === 'previsto'
                  const eCancelada = lancamento.status === 'cancelado'
                  const cartaoDoItem = cartaoDaConta(lancamento.contaId)
                  const eAgendado = !ePrevista && !ePerna && aindaNaoAconteceu(lancamento, hoje)
                  // No cartão a data é a da compra e a caixa é o vencimento: antecipar é pagar a fatura.
                  const podeConfirmarAgendado =
                    eAgendado &&
                    cartaoDoItem === null &&
                    lancamento.compraParceladaId === null &&
                    lancamento.categoriaId !== null &&
                    lancamento.tipo !== 'transferencia'
                  // Só vale o aviso se a série ainda gera e a data cai dentro do que ela repõe.
                  const voltaComoPrevista =
                    lancamento.templateId !== null &&
                    recorrenciasAtivas.has(lancamento.templateId) &&
                    lancamento.dataCaixa >= hoje
                  const confirmandoEste =
                    confirmando?.id === lancamento.id && confirmando.lugar === 'mes' ? confirmando.acao : null
                  const compraId = lancamento.compraParceladaId
                  const parcelaDaCompra =
                    compraId !== null && lancamento.parcelaNum !== null && lancamento.parcelaTotal !== null
                      ? { compraId, num: lancamento.parcelaNum, total: lancamento.parcelaTotal }
                      : null
                  const emConfirmacao =
                    confirmandoEste === 'apagar' ||
                    confirmandoEste === 'apagar-parcela' ||
                    confirmandoEste === 'apagar-compra' ||
                    confirmandoEste === 'cancelar'
                  // Toda linha tem ao menos Apagar, Editar ou Cancelar.
                  const temMenu = true
                  return (
                    <li
                      key={lancamento.id}
                      className={ePrevista || eAgendado ? 'previsto' : eCancelada ? 'cancelado' : undefined}
                    >
                      <span className={`item-icone ${lancamento.tipo}`}>{iconeDoItem(lancamento)}</span>
                      <div className="item-corpo">
                        <strong className="item-titulo">{lancamento.titulo}</strong>
                        <span className="item-meta">
                          {ePerna
                            ? ''
                            : ((lancamento.categoriaId !== null && nomesDeCategoria.get(lancamento.categoriaId)) ||
                              'Sem categoria') + ' · '}
                          {nomesDeConta.get(lancamento.contaId) ?? 'Conta arquivada'}
                        </span>
                        {/* Na fatura do cartão o caixa cai depois da compra; mostra as duas. */}
                        {lancamento.dataCompetencia !== lancamento.dataCaixa && (
                          <small>compra em {formatarDiaMes(lancamento.dataCompetencia)}</small>
                        )}
                        {(ePrevista || eAgendado || eCancelada || ePerna || lancamento.parcelaOrdem !== null || parcelaDaCompra !== null) && (
                          <div className="item-selos">
                            {ePrevista && <Selo tom="previsto">Previsto</Selo>}
                            {eAgendado && <Selo tom="previsto">{cartaoDoItem === null ? 'Agendado' : 'Na fatura'}</Selo>}
                            {eCancelada && <Selo tom="cancelado">Cancelado</Selo>}
                            {ePerna && <Selo tom="transferencia">Transferência</Selo>}
                            {lancamento.parcelaOrdem !== null && (
                              <Selo tom="neutro">
                                {lancamento.templateId !== null && parcelasPorRecorrencia.has(lancamento.templateId)
                                  ? `parcela ${lancamento.parcelaOrdem} de ${parcelasPorRecorrencia.get(lancamento.templateId)}`
                                  : `parcela ${lancamento.parcelaOrdem}`}
                              </Selo>
                            )}
                            {parcelaDaCompra !== null && (
                              <Selo tom="neutro">
                                parcela {parcelaDaCompra.num} de {parcelaDaCompra.total}
                              </Selo>
                            )}
                          </div>
                        )}
                      </div>
                      <div className="item-lado">
                        <span className={`item-valor ${lancamento.tipo}`}>
                          {sinal} {formatarCentavos(lancamento.valorCentavos)}
                        </span>
                        {!emConfirmacao && (
                          <div className="item-botoes">
                            {ePrevista && confirmandoEste !== 'ajustar' && (
                              <button
                                type="button"
                                className="pequeno destaque-acao"
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
                            {podeConfirmarAgendado && (
                              <button
                                type="button"
                                className="pequeno destaque-acao"
                                disabled={apagando !== null}
                                aria-label={`Confirmar ${lancamento.titulo}`}
                                onClick={() => {
                                  const { tipo, categoriaId } = lancamento
                                  if (tipo === 'transferencia' || categoriaId === null) return
                                  void confirmarAgendado(lancamento, tipo, categoriaId)
                                }}
                              >
                                Confirmar
                              </button>
                            )}
                            {temMenu && (
                              <MenuDeAcoes rotulo={`Mais ações de ${lancamento.titulo}`}>
                                {parcelaDaCompra !== null && (
                                  <>
                                    <button
                                      type="button"
                                      disabled={apagando !== null}
                                      aria-label={`Editar parcela ${parcelaDaCompra.num} de ${lancamento.titulo}`}
                                      onClick={() => {
                                        setFalhaDeAcao(null)
                                        setConfirmando(null)
                                        setEditando(lancamento)
                                      }}
                                    >
                                      Editar parcela
                                    </button>
                                    <button
                                      type="button"
                                      disabled={apagando !== null}
                                      aria-label={`Editar compra ${lancamento.titulo}`}
                                      onClick={() => {
                                        setFalhaDeAcao(null)
                                        setConfirmando({ id: lancamento.id, acao: 'editar-compra', lugar: 'mes' })
                                      }}
                                    >
                                      Editar compra
                                    </button>
                                    {/* A primeira parcela é a âncora das outras: o servidor só deixa apagar a compra inteira. */}
                                    {parcelaDaCompra.num !== 1 && (
                                      <button
                                        type="button"
                                        className="perigo"
                                        disabled={apagando !== null}
                                        aria-label={`Apagar parcela ${parcelaDaCompra.num} de ${lancamento.titulo}`}
                                        onClick={() =>
                                          setConfirmando({ id: lancamento.id, acao: 'apagar-parcela', lugar: 'mes' })
                                        }
                                      >
                                        Apagar parcela
                                      </button>
                                    )}
                                    <button
                                      type="button"
                                      className="perigo"
                                      disabled={apagando !== null}
                                      aria-label={`Apagar compra ${lancamento.titulo}`}
                                      onClick={() => void pedirApagarCompra(lancamento, parcelaDaCompra.compraId)}
                                    >
                                      Apagar compra
                                    </button>
                                  </>
                                )}
                                {parcelaDaCompra === null && !ePerna && !eCancelada && (
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
                                {ePrevista && (
                                  <button
                                    type="button"
                                    className="perigo"
                                    disabled={apagando !== null}
                                    aria-label={`Cancelar ${lancamento.titulo}`}
                                    onClick={() => setConfirmando({ id: lancamento.id, acao: 'cancelar', lugar: 'mes' })}
                                  >
                                    Cancelar
                                  </button>
                                )}
                                {/* Apagar prevista é recusado pela API: o caminho é cancelar. */}
                                {!ePrevista && parcelaDaCompra === null && (
                                  <button
                                    type="button"
                                    className="perigo"
                                    disabled={apagando !== null}
                                    aria-label={`Apagar ${lancamento.titulo}`}
                                    onClick={() => setConfirmando({ id: lancamento.id, acao: 'apagar', lugar: 'mes' })}
                                  >
                                    Apagar
                                  </button>
                                )}
                              </MenuDeAcoes>
                            )}
                          </div>
                        )}
                      </div>
                      {emConfirmacao && (
                        <div className="item-acoes">
                          {confirmandoEste === 'apagar-parcela' && parcelaDaCompra !== null && (
                            <>
                              <span className="aviso-acao">Apaga só esta parcela. A soma da compra muda.</span>
                              <button
                                type="button"
                                className="pequeno perigo forte"
                                disabled={apagando !== null}
                                aria-label={`Confirmar apagar parcela ${parcelaDaCompra.num} de ${lancamento.titulo}`}
                                onClick={() => void apagar(lancamento)}
                              >
                                Confirmar apagar parcela
                              </button>
                              <button
                                type="button"
                                className="pequeno"
                                disabled={apagando !== null}
                                onClick={() => setConfirmando(null)}
                              >
                                Manter parcela
                              </button>
                            </>
                          )}
                          {confirmandoEste === 'apagar-compra' && parcelaDaCompra !== null && (
                            <>
                              <span className="aviso-acao">
                                Apaga a compra inteira: somem {confirmando?.quantidade ?? parcelaDaCompra.total} parcelas.
                              </span>
                              <button
                                type="button"
                                className="pequeno perigo forte"
                                disabled={apagando !== null}
                                aria-label={`Confirmar apagar compra ${lancamento.titulo}`}
                                onClick={() => void apagarCompra(lancamento, parcelaDaCompra.compraId)}
                              >
                                Confirmar apagar compra
                              </button>
                              <button
                                type="button"
                                className="pequeno"
                                disabled={apagando !== null}
                                onClick={() => setConfirmando(null)}
                              >
                                Manter compra
                              </button>
                            </>
                          )}
                          {confirmandoEste === 'cancelar' && (
                            <>
                              <button
                                type="button"
                                className="pequeno perigo forte"
                                disabled={apagando !== null}
                                aria-label={`Confirmar cancelar ${lancamento.titulo}`}
                                onClick={() => void cancelarOcorrencia(lancamento)}
                              >
                                Confirmar cancelar
                              </button>
                              <button
                                type="button"
                                className="pequeno"
                                disabled={apagando !== null}
                                onClick={() => setConfirmando(null)}
                              >
                                Manter
                              </button>
                            </>
                          )}
                          {confirmandoEste === 'apagar' && (
                            <>
                              {ePerna && <span className="aviso-acao">Apaga as duas pernas da transferência.</span>}
                              {voltaComoPrevista && <span className="aviso-acao">Essa ocorrência volta como prevista.</span>}
                              <button
                                type="button"
                                className="pequeno perigo forte"
                                disabled={apagando !== null}
                                aria-label={`Confirmar apagar ${lancamento.titulo}`}
                                onClick={() => void apagar(lancamento)}
                              >
                                Confirmar apagar
                              </button>
                              <button
                                type="button"
                                className="pequeno"
                                disabled={apagando !== null}
                                onClick={() => setConfirmando(null)}
                              >
                                Cancelar
                              </button>
                            </>
                          )}
                        </div>
                      )}
                      {confirmandoEste === 'editar-compra' && parcelaDaCompra !== null && apoio.tipo === 'ok' && (
                        <FormularioDeCompra
                          parcela={lancamento}
                          categorias={apoio.categorias}
                          aoSalvar={(dados) => editarCompra(lancamento, parcelaDaCompra.compraId, dados)}
                          aoVoltar={() => setConfirmando(null)}
                        />
                      )}
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
            </div>
          ))}
        </>
      )}
      {painelVisivel && (
        <Painel titulo={tituloDoPainel} aoFechar={fecharPainel}>
          {editando === null && contas !== null && (
            <div role="group" aria-label="Tipo de registro" className="segmentos cheio">
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
          {editando === null && contas !== null && modoDoFormulario === 'transferencia' && (
            <FormularioDeTransferencia
              key={`transferencia-${versaoDoFormulario}`}
              contas={contas}
              aoSalvar={() => {
                setVersaoDoFormulario((n) => n + 1)
                setPainelAberto(false)
                depoisDeMudar()
              }}
              aoSessaoExpirar={aoSessaoExpirar}
            />
          )}
          {(editando !== null || modoDoFormulario === 'lancamento') &&
            (apoio.tipo === 'ok' && contas !== null ? (
              <FormularioDeLancamento
                key={editando?.id ?? `novo-${versaoDoFormulario}`}
                contas={contas}
                categorias={apoio.categorias}
                inicial={editando}
                aoSalvar={() => {
                  if (editando === null) setVersaoDoFormulario((n) => n + 1)
                  fecharPainel()
                  depoisDeMudar()
                }}
                aoNaoExistir={() => {
                  fecharPainel()
                  setFalhaDeAcao(MENSAGEM_NAO_EXISTE)
                  depoisDeMudar()
                }}
                aoCancelar={editando === null ? null : fecharPainel}
                aoSessaoExpirar={aoSessaoExpirar}
              />
            ) : (
              apoio.tipo !== 'erro' && <Carregando texto="Preparando o formulário..." linhas={4} />
            ))}
        </Painel>
      )}
    </section>
  )
}
