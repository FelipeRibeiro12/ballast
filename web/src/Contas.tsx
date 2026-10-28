import { useCallback, useEffect, useRef, useState } from 'react'
import type { FormEvent, ReactNode } from 'react'
import {
  ContaInvalidaError,
  ContaNaoEncontradaError,
  atualizarConta,
  deleteConta,
  fetchMfa,
  fetchResumoExclusao,
  LimiteTentativasError,
  NomeDeContaRepetidoError,
  ServicoIndisponivelError,
  SessaoExpiradaError,
  fetchCategorias,
  fetchContas,
  patchConta,
  postConta,
  putCofrinho,
  putCategoriasPermitidas,
} from './api'
import type { AtualizarContaRequest, Categoria, Conta, CriarContaRequest, ResumoExclusaoConta } from './api'
import Faturas from './Faturas'
import { centavosParaCampo, formatarCentavos, lerCentavos, lerValorPositivo } from './dinheiro'
import { IconeBanco, IconeBeneficio, IconeCartao, IconeCripto, IconeDinheiro, IconeInvestimentos, IconeMais } from './icones'
import Painel from './Painel'
import { hashDaSecao } from './rota'
import { Carregando, EstadoVazio, Selo } from './ui'

type EstadoLista =
  | { tipo: 'carregando' }
  | { tipo: 'erro'; mensagem: string }
  | { tipo: 'ok'; contas: Conta[] }

type EstadoCategorias =
  | { tipo: 'carregando' }
  | { tipo: 'erro' }
  | { tipo: 'ok'; despesas: Categoria[] }

type TipoDeConta = Conta['tipo']

const ROTULO_DO_TIPO: Record<TipoDeConta, string> = {
  corrente: 'Corrente',
  poupanca: 'Poupança',
  dinheiro: 'Dinheiro',
  cartao_credito: 'Cartão de crédito',
  beneficio: 'Vale-benefício',
  cripto: 'Cripto',
  investimento: 'Investimento',
  cofrinho: 'Cofrinho',
}

const TIPOS: TipoDeConta[] = ['corrente', 'poupanca', 'dinheiro', 'cartao_credito', 'beneficio', 'cripto', 'investimento', 'cofrinho']

const ICONE_DO_TIPO: Record<TipoDeConta, ReactNode> = {
  corrente: <IconeBanco />,
  poupanca: <IconeBanco />,
  dinheiro: <IconeDinheiro />,
  cartao_credito: <IconeCartao />,
  beneficio: <IconeBeneficio />,
  cripto: <IconeCripto />,
  investimento: <IconeInvestimentos />,
  cofrinho: <IconeDinheiro />,
}

// "110,5" ou "110" viram "110.5" para a API. Nulo fora de 0,01 a 1000 ou com mais de 2 casas.
function lerPercentualDoCdi(texto: string): string | null {
  const limpo = texto.trim().replace(',', '.')
  if (!/^\d{1,4}(\.\d{1,2})?$/.test(limpo)) return null
  const valor = Number(limpo)
  return valor > 0 && valor <= 1000 ? limpo : null
}

// Só para a largura da barra: percentual inteiro do limite já usado, calculado em BigInt.
function percentualUsado(limiteCentavos: number, disponivelCentavos: number): number {
  const limite = BigInt(limiteCentavos)
  if (limite <= 0n) return 0
  const usado = limite - BigInt(disponivelCentavos)
  if (usado <= 0n) return 0
  if (usado >= limite) return 100
  return Number((usado * 100n) / limite)
}

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

function mensagemDeCategorias(erro: unknown): string {
  if (erro instanceof ContaNaoEncontradaError) return 'Essa conta não existe mais.'
  if (erro instanceof ContaInvalidaError && erro.mensagemServidor !== null) return erro.mensagemServidor
  if (erro instanceof ServicoIndisponivelError) return MENSAGEM_INDISPONIVEL
  return 'Não foi possível salvar as categorias. Tente de novo.'
}

const MENSAGEM_SEM_CONTA_DE_PAGAMENTO = 'Crie uma conta corrente ou poupança para pagar o cartão.'

// Só inteiro puro de 1 a 31; "1e1" e "2.5" são recusados.
function lerDiaDoMes(texto: string): number | null {
  if (!/^\d{1,2}$/.test(texto.trim())) return null
  const dia = Number(texto)
  return dia >= 1 && dia <= 31 ? dia : null
}

// Nenhuma marcada vale como "qualquer categoria" (ADR-034); o texto avisa para ninguém achar que bloqueou tudo.
function CategoriasPermitidasCampo({
  categorias,
  marcadas,
  aoAlternar,
  aoTentarDeNovo,
}: {
  categorias: EstadoCategorias
  marcadas: string[]
  aoAlternar: (categoriaId: string) => void
  aoTentarDeNovo: () => void
}) {
  return (
    <fieldset className="categorias-permitidas">
      <legend>O que esta conta pode pagar</legend>
      <p id="categorias-permitidas-ajuda" className="categorias-permitidas-ajuda">
        Marque as categorias de despesa que a conta aceita. Sem nenhuma marcada, ela aceita qualquer categoria. Receita
        não tem restrição.
      </p>
      {categorias.tipo === 'carregando' && <Carregando texto="Carregando categorias..." linhas={2} />}
      {categorias.tipo === 'erro' && (
        <>
          <p role="alert" className="erro">
            Não foi possível carregar as categorias.
          </p>
          <button type="button" onClick={aoTentarDeNovo}>
            Tentar de novo
          </button>
        </>
      )}
      {categorias.tipo === 'ok' && categorias.despesas.length === 0 && (
        <p>Nenhuma categoria de despesa cadastrada.</p>
      )}
      {categorias.tipo === 'ok' && categorias.despesas.length > 0 && (
        <ul className="categorias-lista" aria-describedby="categorias-permitidas-ajuda">
          {categorias.despesas.map((categoria) => (
            <li key={categoria.id}>
              <label>
                <input
                  type="checkbox"
                  checked={marcadas.includes(categoria.id)}
                  onChange={() => aoAlternar(categoria.id)}
                />
                {categoria.nome}
              </label>
            </li>
          ))}
        </ul>
      )}
    </fieldset>
  )
}

function alternarMarca(marcadas: string[], categoriaId: string): string[] {
  return marcadas.includes(categoriaId) ? marcadas.filter((id) => id !== categoriaId) : [...marcadas, categoriaId]
}

// Só manda ids que ainda são categoria de despesa da lista carregada, na ordem da tela.
function idsMarcados(categorias: EstadoCategorias, marcadas: string[]): string[] {
  if (categorias.tipo !== 'ok') return []
  return categorias.despesas.filter((categoria) => marcadas.includes(categoria.id)).map((categoria) => categoria.id)
}

function EditarCategorias({
  conta,
  categorias,
  aoTentarDeNovo,
  aoSalvar,
  aoNaoExistir,
  aoSessaoExpirar,
}: {
  conta: Conta
  categorias: EstadoCategorias
  aoTentarDeNovo: () => void
  aoSalvar: (conta: Conta) => void
  aoNaoExistir: () => void
  aoSessaoExpirar: () => void
}) {
  const [marcadas, setMarcadas] = useState<string[]>(conta.categoriasPermitidas ?? [])
  const [enviando, setEnviando] = useState(false)
  const [mensagem, setMensagem] = useState<string | null>(null)
  // O state só atualiza no próximo render; o ref barra o segundo submit síncrono.
  const emVoo = useRef(false)

  async function enviar(evento: FormEvent<HTMLFormElement>): Promise<void> {
    evento.preventDefault()
    if (emVoo.current) return
    // Sem a lista carregada o envio mandaria vazio e liberaria tudo sem o usuário ter pedido.
    if (categorias.tipo !== 'ok') {
      setMensagem('Espere as categorias carregarem para salvar.')
      return
    }
    emVoo.current = true
    setEnviando(true)
    setMensagem(null)
    try {
      aoSalvar(await putCategoriasPermitidas(conta.id, idsMarcados(categorias, marcadas)))
    } catch (erro) {
      if (erro instanceof SessaoExpiradaError) {
        aoSessaoExpirar()
        return
      }
      if (erro instanceof ContaNaoEncontradaError) {
        aoNaoExistir()
        return
      }
      setMensagem(mensagemDeCategorias(erro))
    } finally {
      emVoo.current = false
      setEnviando(false)
    }
  }

  return (
    <form onSubmit={(evento) => void enviar(evento)} aria-label={`Categorias de ${conta.nome}`}>
      <CategoriasPermitidasCampo
        categorias={categorias}
        marcadas={marcadas}
        aoAlternar={(id) => setMarcadas((atual) => alternarMarca(atual, id))}
        aoTentarDeNovo={aoTentarDeNovo}
      />
      {mensagem !== null && (
        <p role="alert" className="erro">
          {mensagem}
        </p>
      )}
      <button type="submit" disabled={enviando}>
        {enviando ? 'Salvando...' : 'Salvar categorias'}
      </button>
    </form>
  )
}

function mensagemDeEdicao(erro: unknown): string {
  if (erro instanceof NomeDeContaRepetidoError) return 'Já existe uma conta com esse nome.'
  if (erro instanceof ContaInvalidaError && erro.mensagemServidor !== null) return erro.mensagemServidor
  if (erro instanceof ServicoIndisponivelError) return MENSAGEM_INDISPONIVEL
  return 'Não foi possível salvar a conta. Tente de novo.'
}

// O saldo inicial do cartão é dívida (negativo no servidor), digitado como positivo, como na criação.
function saldoInicialNaTela(conta: Conta): string {
  if (conta.saldoInicialCentavos === 0) return ''
  return centavosParaCampo(conta.tipo === 'cartao_credito' ? -conta.saldoInicialCentavos : conta.saldoInicialCentavos)
}

function EditarConta({
  conta,
  contasDePagamento,
  aoSalvar,
  aoNaoExistir,
  aoSessaoExpirar,
}: {
  conta: Conta
  contasDePagamento: Conta[]
  aoSalvar: (conta: Conta) => void
  aoNaoExistir: () => void
  aoSessaoExpirar: () => void
}) {
  const eCartao = conta.cartao !== null
  // Cripto e investimento não têm saldo em reais (ADR-035, ADR-037).
  const eCripto = conta.tipo === 'cripto' || conta.tipo === 'investimento'
  const [nome, setNome] = useState(conta.nome)
  const [saldo, setSaldo] = useState(saldoInicialNaTela(conta))
  const [limite, setLimite] = useState(conta.cartao === null ? '' : centavosParaCampo(conta.cartao.limiteCentavos))
  const [pagamento, setPagamento] = useState(conta.cartao?.contaPagamentoId ?? '')
  const [percentual, setPercentual] = useState((conta.percentualCdi ?? '').replace('.', ','))
  const [enviando, setEnviando] = useState(false)
  const [mensagem, setMensagem] = useState<string | null>(null)
  // O state só atualiza no próximo render; o ref barra o segundo submit síncrono.
  const emVoo = useRef(false)

  async function enviar(evento: FormEvent<HTMLFormElement>): Promise<void> {
    evento.preventDefault()
    if (emVoo.current) return
    const dados: AtualizarContaRequest = { nome }
    if (!eCripto) {
      const lido = saldo.trim() === '' ? 0 : lerCentavos(saldo)
      if (lido === null) {
        setMensagem('Saldo inválido. Use o formato 1.234,56, até 90.071.992.547.409,91.')
        return
      }
      if (eCartao && lido < 0) {
        setMensagem('Digite a fatura em aberto como valor positivo.')
        return
      }
      dados.saldoInicialCentavos = eCartao && lido !== 0 ? -lido : lido
    }
    if (eCartao) {
      const limiteCentavos = lerValorPositivo(limite)
      if (limiteCentavos === null) {
        setMensagem('Limite inválido. Use o formato 1.234,56, maior que zero.')
        return
      }
      // Só manda a conta de pagamento se mudou: a atual pode estar arquivada, e reenviar seria recusado.
      const pagamentoMudou = pagamento !== '' && pagamento !== conta.cartao?.contaPagamentoId
      dados.cartao = { limiteCentavos, ...(pagamentoMudou ? { contaPagamentoId: pagamento } : {}) }
    }
    let percentualParaEnviar: string | null = null
    if (conta.tipo === 'cofrinho') {
      percentualParaEnviar = lerPercentualDoCdi(percentual)
      if (percentualParaEnviar === null) {
        setMensagem('Percentual do CDI inválido. Use de 0,01 a 1000, com até duas casas.')
        return
      }
    }
    emVoo.current = true
    setEnviando(true)
    setMensagem(null)
    try {
      const atualizada = await atualizarConta(conta.id, dados)
      aoSalvar(percentualParaEnviar === null ? atualizada : await putCofrinho(conta.id, percentualParaEnviar))
    } catch (erro) {
      if (erro instanceof SessaoExpiradaError) {
        aoSessaoExpirar()
        return
      }
      if (erro instanceof ContaNaoEncontradaError) {
        aoNaoExistir()
        return
      }
      setMensagem(mensagemDeEdicao(erro))
    } finally {
      emVoo.current = false
      setEnviando(false)
    }
  }

  return (
    <form onSubmit={(evento) => void enviar(evento)} aria-label={`Editar ${conta.nome}`}>
      <div className="campo">
        <label htmlFor="editar-conta-nome">Nome</label>
        <input
          id="editar-conta-nome"
          type="text"
          maxLength={100}
          required
          value={nome}
          onChange={(evento) => setNome(evento.target.value)}
        />
      </div>
      {conta.tipo === 'cofrinho' && (
        <div className="campo">
          <label htmlFor="editar-conta-percentual">% do CDI</label>
          <input
            id="editar-conta-percentual"
            type="text"
            inputMode="decimal"
            autoComplete="off"
            required
            value={percentual}
            onChange={(evento) => setPercentual(evento.target.value)}
          />
        </div>
      )}
      {!eCripto && (
        <div className="campo">
          <label htmlFor="editar-conta-saldo">
            {eCartao ? 'Fatura em aberto quando o cartão entrou no app' : 'Saldo inicial em reais'}
          </label>
          <input
            id="editar-conta-saldo"
            type="text"
            inputMode="decimal"
            autoComplete="off"
            placeholder="0,00"
            value={saldo}
            onChange={(evento) => setSaldo(evento.target.value)}
          />
          <small>Muda o saldo de hoje pela diferença; os lançamentos ficam como estão.</small>
        </div>
      )}
      {eCartao && (
        <>
          <div className="campo">
            <label htmlFor="editar-conta-limite">Limite em reais</label>
            <input
              id="editar-conta-limite"
              type="text"
              inputMode="decimal"
              autoComplete="off"
              required
              value={limite}
              onChange={(evento) => setLimite(evento.target.value)}
            />
          </div>
          <div className="campo">
            <label htmlFor="editar-conta-pagamento">Conta de pagamento</label>
            <select id="editar-conta-pagamento" value={pagamento} onChange={(evento) => setPagamento(evento.target.value)}>
              {contasDePagamento.every((c) => c.id !== pagamento) && pagamento !== '' && (
                <option value={pagamento}>Conta atual (arquivada)</option>
              )}
              {contasDePagamento.map((opcao) => (
                <option key={opcao.id} value={opcao.id}>
                  {opcao.nome}
                </option>
              ))}
            </select>
            <small>Fechamento e vencimento não mudam: as compras já lançadas dependem deles.</small>
          </div>
        </>
      )}
      {mensagem !== null && (
        <p role="alert" className="erro">
          {mensagem}
        </p>
      )}
      <button type="submit" className="primario" disabled={enviando}>
        {enviando ? 'Salvando...' : 'Salvar'}
      </button>
    </form>
  )
}

function plural(quantidade: number, singular: string, varios: string): string {
  return `${quantidade} ${quantidade === 1 ? singular : varios}`
}

type EstadoResumo =
  | { tipo: 'carregando' }
  | { tipo: 'erro'; mensagem: string }
  | { tipo: 'ok'; resumo: ResumoExclusaoConta; mfaAtivo: boolean }

function mensagemDeLimite(erro: LimiteTentativasError): string {
  if (erro.segundos === null) return 'Muitas tentativas. Tente de novo em instantes.'
  return `Muitas tentativas. Tente de novo em ${erro.segundos} ${erro.segundos === 1 ? 'segundo' : 'segundos'}.`
}

// Apagar não tem volta: o resumo diz o que sai junto e o nome digitado confirma (ADR-036).
function ApagarConta({
  conta,
  aoApagar,
  aoNaoExistir,
  aoSessaoExpirar,
}: {
  conta: Conta
  aoApagar: () => void
  aoNaoExistir: () => void
  aoSessaoExpirar: () => void
}) {
  const [resumo, setResumo] = useState<EstadoResumo>({ tipo: 'carregando' })
  const [confirmacao, setConfirmacao] = useState('')
  const [codigo, setCodigo] = useState('')
  const [enviando, setEnviando] = useState(false)
  const [mensagem, setMensagem] = useState<string | null>(null)
  const emVoo = useRef(false)

  useEffect(() => {
    let ativo = true
    // O status do MFA vem junto: sem saber se o código é exigido, o botão não pode liberar.
    Promise.all([fetchResumoExclusao(conta.id), fetchMfa()]).then(
      ([lido, mfa]) => {
        if (ativo) setResumo({ tipo: 'ok', resumo: lido, mfaAtivo: mfa.ativo })
      },
      (erro: unknown) => {
        if (!ativo) return
        if (erro instanceof SessaoExpiradaError) {
          aoSessaoExpirar()
          return
        }
        if (erro instanceof ContaNaoEncontradaError) {
          aoNaoExistir()
          return
        }
        setResumo({ tipo: 'erro', mensagem: mensagemDeLeitura(erro) })
      },
    )
    return () => {
      ativo = false
    }
  }, [conta.id, aoNaoExistir, aoSessaoExpirar])

  const mfaAtivo = resumo.tipo === 'ok' && resumo.mfaAtivo
  const confirmado = confirmacao.trim() === conta.nome && (!mfaAtivo || codigo.trim() !== '')

  async function apagar(evento: FormEvent<HTMLFormElement>): Promise<void> {
    evento.preventDefault()
    if (emVoo.current || !confirmado) return
    emVoo.current = true
    setEnviando(true)
    setMensagem(null)
    try {
      await deleteConta(conta.id, mfaAtivo ? codigo.trim() : undefined)
      aoApagar()
    } catch (erro) {
      if (erro instanceof SessaoExpiradaError) {
        aoSessaoExpirar()
        return
      }
      if (erro instanceof ContaNaoEncontradaError) {
        aoNaoExistir()
        return
      }
      if (erro instanceof ContaInvalidaError && erro.mensagemServidor === 'código inválido') {
        setMensagem('Código inválido.')
        setCodigo('')
      } else if (erro instanceof LimiteTentativasError) {
        setMensagem(mensagemDeLimite(erro))
        setCodigo('')
      } else if (erro instanceof ContaInvalidaError && erro.mensagemServidor !== null) {
        setMensagem(erro.mensagemServidor)
      } else if (erro instanceof ServicoIndisponivelError) {
        setMensagem(MENSAGEM_INDISPONIVEL)
      } else {
        setMensagem('Não foi possível apagar a conta. Tente de novo.')
      }
    } finally {
      emVoo.current = false
      setEnviando(false)
    }
  }

  return (
    <form onSubmit={(evento) => void apagar(evento)} aria-label={`Apagar ${conta.nome}`}>
      {resumo.tipo === 'carregando' && <Carregando texto="Contando o que sai junto..." linhas={2} />}
      {resumo.tipo === 'erro' && (
        <p role="alert" className="erro">
          {resumo.mensagem}
        </p>
      )}
      {resumo.tipo === 'ok' && (
        <div className="aviso-exclusao">
          <p>Isto não tem volta. Sai junto com a conta:</p>
          <ul>
            <li>{plural(resumo.resumo.lancamentos, 'lançamento', 'lançamentos')}</li>
            <li>{plural(resumo.resumo.recorrencias, 'recorrência', 'recorrências')}</li>
            {resumo.resumo.movimentosCripto > 0 && (
              <li>{plural(resumo.resumo.movimentosCripto, 'movimento de cripto', 'movimentos de cripto')}</li>
            )}
            {resumo.resumo.titulosRendaFixa > 0 && (
              <li>{plural(resumo.resumo.titulosRendaFixa, 'título de renda fixa', 'títulos de renda fixa')}</li>
            )}
          </ul>
          {resumo.resumo.transferencias > 0 && (
            <p>
              {plural(resumo.resumo.transferencias, 'transferência com outra conta vira', 'transferências com outras contas viram')}{' '}
              lançamento comum lá, e o saldo da outra conta não muda.
            </p>
          )}
          <p>Para só esconder a conta e manter o histórico, use Arquivar.</p>
        </div>
      )}
      <div className="campo">
        <label htmlFor="apagar-conta-nome">Digite o nome da conta para confirmar</label>
        <input
          id="apagar-conta-nome"
          type="text"
          autoComplete="off"
          value={confirmacao}
          onChange={(evento) => setConfirmacao(evento.target.value)}
        />
      </div>
      {mfaAtivo && (
        <div className="campo">
          <label htmlFor="apagar-conta-codigo">Código do app ou de recuperação</label>
          <input
            id="apagar-conta-codigo"
            type="text"
            autoComplete="one-time-code"
            autoCapitalize="none"
            spellCheck={false}
            maxLength={32}
            value={codigo}
            onChange={(evento) => setCodigo(evento.target.value)}
          />
        </div>
      )}
      {mensagem !== null && (
        <p role="alert" className="erro">
          {mensagem}
        </p>
      )}
      <button type="submit" className="perigo forte" disabled={!confirmado || enviando || resumo.tipo !== 'ok'}>
        {enviando ? 'Apagando...' : 'Apagar de vez'}
      </button>
    </form>
  )
}

function NovaConta({
  contasDePagamento,
  categorias,
  aoTentarCategoriasDeNovo,
  aoCriar,
  aoSessaoExpirar,
}: {
  // Contas ativas, correntes ou poupanças: as únicas que o servidor aceita para pagar um cartão.
  contasDePagamento: Conta[]
  categorias: EstadoCategorias
  aoTentarCategoriasDeNovo: () => void
  aoCriar: (conta: Conta) => void
  aoSessaoExpirar: () => void
}) {
  const [nome, setNome] = useState('')
  const [tipo, setTipo] = useState<TipoDeConta>('corrente')
  const [saldo, setSaldo] = useState('')
  const [limite, setLimite] = useState('')
  const [diaFechamento, setDiaFechamento] = useState('')
  const [diaVencimento, setDiaVencimento] = useState('')
  const [pagamentoEscolhido, setPagamentoEscolhido] = useState('')
  const [percentual, setPercentual] = useState('')
  const [categoriasMarcadas, setCategoriasMarcadas] = useState<string[]>([])
  const [enviando, setEnviando] = useState(false)
  const [mensagem, setMensagem] = useState<string | null>(null)
  // O state só atualiza no próximo render; o ref barra o segundo submit síncrono.
  const emVoo = useRef(false)

  const eCartao = tipo === 'cartao_credito'
  const eBeneficio = tipo === 'beneficio'
  // Conta cripto e de investimento não têm saldo em reais: o valor vem das posições e dos títulos.
  const eCripto = tipo === 'cripto' || tipo === 'investimento'
  const contaPagamentoId = contasDePagamento.some((c) => c.id === pagamentoEscolhido)
    ? pagamentoEscolhido
    : (contasDePagamento[0]?.id ?? '')
  const semContaDePagamento = eCartao && contasDePagamento.length === 0

  async function enviar(evento: FormEvent<HTMLFormElement>): Promise<void> {
    evento.preventDefault()
    if (emVoo.current) return
    // Campo vazio vale zero; texto fora do formato não chega à API.
    const saldoInicialCentavos = eCripto || saldo.trim() === '' ? 0 : lerCentavos(saldo)
    if (saldoInicialCentavos === null) {
      setMensagem('Saldo inválido. Use o formato 1.234,56, até 90.071.992.547.409,91.')
      return
    }
    let cartao: CriarContaRequest['cartao']
    let saldoParaEnviar = saldoInicialCentavos
    if (eCartao) {
      const limiteCentavos = lerValorPositivo(limite)
      if (limiteCentavos === null) {
        setMensagem('Limite inválido. Use o formato 1.234,56, maior que zero.')
        return
      }
      const fechamento = lerDiaDoMes(diaFechamento)
      const vencimento = lerDiaDoMes(diaVencimento)
      if (fechamento === null || vencimento === null) {
        setMensagem('Dia de fechamento e de vencimento devem ser de 1 a 31.')
        return
      }
      if (contaPagamentoId === '') {
        setMensagem(MENSAGEM_SEM_CONTA_DE_PAGAMENTO)
        return
      }
      // O usuário digita a fatura em aberto como positivo; no saldo ela é dívida.
      if (saldoInicialCentavos < 0) {
        setMensagem('Digite a fatura em aberto como valor positivo.')
        return
      }
      saldoParaEnviar = saldoInicialCentavos === 0 ? 0 : -saldoInicialCentavos
      cartao = { limiteCentavos, diaFechamento: fechamento, diaVencimento: vencimento, contaPagamentoId }
    }
    const percentualCdi = tipo === 'cofrinho' ? lerPercentualDoCdi(percentual) : null
    if (tipo === 'cofrinho' && percentualCdi === null) {
      setMensagem('Percentual do CDI inválido. Use de 0,01 a 1000, com até duas casas.')
      return
    }
    // Sem a lista carregada o envio mandaria vazio e a conta nasceria aceitando tudo sem o usuário pedir.
    if (eBeneficio && categorias.tipo !== 'ok') {
      setMensagem('Espere as categorias carregarem para criar o benefício.')
      return
    }
    emVoo.current = true
    setEnviando(true)
    setMensagem(null)
    try {
      const criada = await postConta({
        nome,
        tipo,
        saldoInicialCentavos: saldoParaEnviar,
        ...(cartao === undefined ? {} : { cartao }),
        ...(eBeneficio ? { categoriasPermitidas: idsMarcados(categorias, categoriasMarcadas) } : {}),
      })
      // A API grava o percentual numa segunda chamada; se ela falhar a conta já existe sem percentual.
      const conta = percentualCdi === null ? criada : await putCofrinho(criada.id, percentualCdi)
      aoCriar(conta)
      setPercentual('')
      setNome('')
      setTipo('corrente')
      setSaldo('')
      setLimite('')
      setDiaFechamento('')
      setDiaVencimento('')
      setCategoriasMarcadas([])
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
      {tipo === 'cofrinho' && (
        <div className="campo">
          <label htmlFor="conta-percentual">% do CDI</label>
          <input
            id="conta-percentual"
            type="text"
            inputMode="decimal"
            autoComplete="off"
            placeholder="100"
            required
            value={percentual}
            onChange={(evento) => setPercentual(evento.target.value)}
          />
        </div>
      )}
      {eCripto ? (
        <p role="status">
          {tipo === 'cripto'
            ? 'Compras, vendas e cotações da conta ficam na aba Investimentos.'
            : 'Os títulos da conta ficam na aba Investimentos.'}
        </p>
      ) : (
        <div className="campo">
          <label htmlFor="conta-saldo">
            {eCartao ? 'Fatura em aberto hoje (opcional)' : 'Saldo inicial em reais (opcional)'}
          </label>
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
      )}
      {eCartao && (
        <>
          <div className="campo">
            <label htmlFor="conta-limite">Limite em reais</label>
            <input
              id="conta-limite"
              type="text"
              inputMode="decimal"
              autoComplete="off"
              placeholder="0,00"
              required
              value={limite}
              onChange={(evento) => setLimite(evento.target.value)}
            />
          </div>
          <div className="campo">
            <label htmlFor="conta-fechamento">Dia de fechamento</label>
            <input
              id="conta-fechamento"
              type="number"
              inputMode="numeric"
              min={1}
              max={31}
              required
              value={diaFechamento}
              onChange={(evento) => setDiaFechamento(evento.target.value)}
            />
          </div>
          <div className="campo">
            <label htmlFor="conta-vencimento">Dia de vencimento</label>
            <input
              id="conta-vencimento"
              type="number"
              inputMode="numeric"
              min={1}
              max={31}
              required
              value={diaVencimento}
              onChange={(evento) => setDiaVencimento(evento.target.value)}
            />
            <small>Meses curtos usam o último dia.</small>
          </div>
          {semContaDePagamento ? (
            <p role="status">{MENSAGEM_SEM_CONTA_DE_PAGAMENTO}</p>
          ) : (
            <div className="campo">
              <label htmlFor="conta-pagamento">Conta de pagamento</label>
              <select
                id="conta-pagamento"
                value={contaPagamentoId}
                required
                onChange={(evento) => setPagamentoEscolhido(evento.target.value)}
              >
                {contasDePagamento.map((conta) => (
                  <option key={conta.id} value={conta.id}>
                    {conta.nome}
                  </option>
                ))}
              </select>
            </div>
          )}
        </>
      )}
      {eBeneficio && (
        <CategoriasPermitidasCampo
          categorias={categorias}
          marcadas={categoriasMarcadas}
          aoAlternar={(id) => setCategoriasMarcadas((atual) => alternarMarca(atual, id))}
          aoTentarDeNovo={aoTentarCategoriasDeNovo}
        />
      )}
      {mensagem !== null && (
        <p id="erro-nova-conta" role="alert" className="erro">
          {mensagem}
        </p>
      )}
      <button type="submit" disabled={enviando || semContaDePagamento}>
        {enviando ? 'Criando...' : 'Criar conta'}
      </button>
    </form>
  )
}

export default function Contas({
  aoSessaoExpirar,
  versaoDeSaldo,
  aoCarregarAtivas,
  aoMudarSaldo,
}: {
  aoSessaoExpirar: () => void
  // Sobe quando um lançamento muda; a lista busca de novo para o saldo reagir.
  versaoDeSaldo: number
  // Entrega a lista de contas ativas sempre que ela muda, para a tela de lançamentos usar.
  aoCarregarAtivas: (contas: Conta[]) => void
  // Avisa o app que o saldo mudou (pagamento de fatura), para as outras telas buscarem de novo.
  aoMudarSaldo: () => void
}) {
  const [faturasDe, setFaturasDe] = useState<string | null>(null)
  const [arquivadas, setArquivadas] = useState(false)
  const [estado, setEstado] = useState<EstadoLista>({ tipo: 'carregando' })
  const [tentativa, setTentativa] = useState(0)
  const [ocupada, setOcupada] = useState<string | null>(null)
  const [falhaDeAcao, setFalhaDeAcao] = useState<string | null>(null)
  const [painelAberto, setPainelAberto] = useState(false)
  const [categoriasDe, setCategoriasDe] = useState<Conta | null>(null)
  const [editando, setEditando] = useState<Conta | null>(null)
  const [apagando, setApagando] = useState<Conta | null>(null)
  const [categorias, setCategorias] = useState<EstadoCategorias>({ tipo: 'carregando' })
  const [tentativaDeCategorias, setTentativaDeCategorias] = useState(0)
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
    let ativo = true
    fetchCategorias().then(
      (todas) => {
        if (ativo) setCategorias({ tipo: 'ok', despesas: todas.filter((categoria) => categoria.tipo === 'despesa') })
      },
      (erro: unknown) => {
        if (!ativo) return
        if (erro instanceof SessaoExpiradaError) {
          aoSessaoExpirar()
          return
        }
        setCategorias({ tipo: 'erro' })
      },
    )
    return () => {
      ativo = false
    }
  }, [tentativaDeCategorias, aoSessaoExpirar])

  useEffect(() => {
    if (!arquivadas && estado.tipo === 'ok') aoCarregarAtivas(estado.contas)
  }, [arquivadas, estado, aoCarregarAtivas])

  function recarregar(): void {
    setEstado({ tipo: 'carregando' })
    setTentativa((n) => n + 1)
  }

  // Estável: o efeito do resumo de exclusão depende dela.
  const fecharApagarSumida = useCallback((): void => {
    setApagando(null)
    setFalhaDeAcao('Essa conta não existe mais.')
    setEstado({ tipo: 'carregando' })
    setTentativa((n) => n + 1)
  }, [])

  function recarregarCategorias(): void {
    setCategorias({ tipo: 'carregando' })
    setTentativaDeCategorias((n) => n + 1)
  }

  const nomesDeCategoria = new Map<string, string>()
  if (categorias.tipo === 'ok') categorias.despesas.forEach((categoria) => nomesDeCategoria.set(categoria.id, categoria.nome))

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

  const contasDePagamento =
    estado.tipo === 'ok' ? estado.contas.filter((c) => c.tipo === 'corrente' || c.tipo === 'poupanca') : []

  return (
    <section aria-labelledby="titulo-contas">
      <header className="pagina-topo">
        <h2 id="titulo-contas">{arquivadas ? 'Contas arquivadas' : 'Contas'}</h2>
        <div className="topo-botoes">
          <button type="button" className="discreto" onClick={alternarVisao}>
            {arquivadas ? 'Voltar às ativas' : 'Ver arquivadas'}
          </button>
          {!arquivadas && (
            <button type="button" className="primario" onClick={() => setPainelAberto(true)}>
              <IconeMais />
              Nova conta
            </button>
          )}
        </div>
      </header>
      {estado.tipo === 'carregando' && <Carregando texto="Carregando contas..." />}
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
            <p className="resumo-total">
              Total: <strong>{formatarCentavos(total)}</strong>
            </p>
          )}
          {estado.contas.length === 0 && (
            <EstadoVazio
              titulo={arquivadas ? 'Nenhuma conta arquivada.' : 'Nenhuma conta ainda.'}
              dica={arquivadas ? undefined : 'Comece por uma conta corrente, poupança ou cartão.'}
              acao={
                arquivadas ? undefined : (
                  <button type="button" className="primario" onClick={() => setPainelAberto(true)}>
                    Criar a primeira conta
                  </button>
                )
              }
            />
          )}
          <ul className="contas">
            {estado.contas.map((conta) => {
              const expandida = faturasDe === conta.id && !arquivadas
              const usado = conta.cartao === null ? 0 : percentualUsado(conta.cartao.limiteCentavos, conta.cartao.disponivelCentavos)
              return (
                <li key={conta.id} className={expandida ? 'cartao conta-cartao expandida' : 'cartao conta-cartao'}>
                  <div className="conta-topo">
                    <span className="conta-icone">{ICONE_DO_TIPO[conta.tipo]}</span>
                    <div className="conta-nome">
                      <strong>{conta.nome}</strong>
                      <span>
                        {ROTULO_DO_TIPO[conta.tipo]}
                        {conta.percentualCdi != null && ` · ${conta.percentualCdi.replace('.', ',')}% do CDI`}
                      </span>
                    </div>
                    {conta.tipo === 'cripto' || conta.tipo === 'investimento' ? (
                      <a className="conta-saldo" href={hashDaSecao('investimentos')}>
                        {conta.tipo === 'cripto' ? 'Ver posições' : 'Ver títulos'}
                      </a>
                    ) : (
                      <span className={conta.saldoCentavos < 0 ? 'conta-saldo negativo' : 'conta-saldo'}>
                        {formatarCentavos(conta.saldoCentavos)}
                      </span>
                    )}
                  </div>
                  {conta.cartao !== null && (
                    <div className="limite">
                      <p>
                        Disponível: <strong>{formatarCentavos(conta.cartao.disponivelCentavos)}</strong> de{' '}
                        {formatarCentavos(conta.cartao.limiteCentavos)}
                      </p>
                      <div className={usado >= 90 ? 'barra critica' : usado >= 70 ? 'barra atencao' : 'barra'} aria-hidden="true">
                        <div style={{ width: `${usado}%` }} />
                      </div>
                      <small>{usado}% do limite em uso</small>
                    </div>
                  )}
                  {conta.categoriasPermitidas !== null && (
                    <div className="conta-categorias">
                      {conta.categoriasPermitidas.length === 0 ? (
                        <p>Aceita qualquer categoria</p>
                      ) : categorias.tipo === 'ok' ? (
                        <ul aria-label={`Categorias permitidas em ${conta.nome}`}>
                          {conta.categoriasPermitidas.map((id) => (
                            <li key={id}>
                              <Selo tom="neutro">{nomesDeCategoria.get(id) ?? 'Categoria desconhecida'}</Selo>
                            </li>
                          ))}
                        </ul>
                      ) : (
                        <p>{conta.categoriasPermitidas.length} categorias permitidas</p>
                      )}
                    </div>
                  )}
                  <div className="conta-acoes">
                    {conta.categoriasPermitidas !== null && !arquivadas && (
                      <button
                        type="button"
                        className="pequeno"
                        aria-label={`Editar categorias de ${conta.nome}`}
                        onClick={() => setCategoriasDe(conta)}
                      >
                        Editar categorias
                      </button>
                    )}
                    {conta.cartao !== null && !arquivadas && (
                      <button
                        type="button"
                        className="pequeno"
                        aria-expanded={faturasDe === conta.id}
                        aria-label={`Faturas de ${conta.nome}`}
                        onClick={() => setFaturasDe((atual) => (atual === conta.id ? null : conta.id))}
                      >
                        Faturas
                      </button>
                    )}
                    {!arquivadas && (
                      <button
                        type="button"
                        className="pequeno"
                        aria-label={`Editar ${conta.nome}`}
                        onClick={() => {
                          setFalhaDeAcao(null)
                          setEditando(conta)
                        }}
                      >
                        Editar
                      </button>
                    )}
                    <button
                      type="button"
                      className="pequeno discreto"
                      disabled={ocupada !== null}
                      aria-label={`${arquivadas ? 'Desarquivar' : 'Arquivar'} ${conta.nome}`}
                      onClick={() => void mudarArquivamento(conta, !arquivadas)}
                    >
                      {arquivadas ? 'Desarquivar' : 'Arquivar'}
                    </button>
                    <button
                      type="button"
                      className="pequeno perigo"
                      aria-label={`Apagar ${conta.nome}`}
                      onClick={() => {
                        setFalhaDeAcao(null)
                        setApagando(conta)
                      }}
                    >
                      Apagar
                    </button>
                  </div>
                  {expandida && (
                    <Faturas
                      cartao={conta}
                      contasAtivas={estado.contas}
                      versaoDeSaldo={versaoDeSaldo}
                      aoMudarSaldo={aoMudarSaldo}
                      aoSessaoExpirar={aoSessaoExpirar}
                    />
                  )}
                </li>
              )
            })}
          </ul>
        </>
      )}
      {painelAberto && !arquivadas && (
        <Painel titulo="Nova conta" aoFechar={() => setPainelAberto(false)}>
          <NovaConta
            contasDePagamento={contasDePagamento}
            categorias={categorias}
            aoTentarCategoriasDeNovo={recarregarCategorias}
            aoCriar={(conta) => {
              setEstado((atual) =>
                atual.tipo === 'ok' ? { tipo: 'ok', contas: [...atual.contas, conta].sort(compararContas) } : atual,
              )
              setPainelAberto(false)
            }}
            aoSessaoExpirar={aoSessaoExpirar}
          />
        </Painel>
      )}
      {editando !== null && (
        <Painel titulo={`Editar ${editando.nome}`} aoFechar={() => setEditando(null)}>
          <EditarConta
            conta={editando}
            contasDePagamento={contasDePagamento}
            aoSalvar={(salva) => {
              setEstado((atual) =>
                atual.tipo === 'ok'
                  ? { tipo: 'ok', contas: atual.contas.map((outra) => (outra.id === salva.id ? salva : outra)).sort(compararContas) }
                  : atual,
              )
              setEditando(null)
              // Saldo inicial novo muda o saldo, a projeção e o Início.
              aoMudarSaldo()
            }}
            aoNaoExistir={() => {
              setEditando(null)
              setFalhaDeAcao('Essa conta não existe mais.')
              recarregar()
            }}
            aoSessaoExpirar={aoSessaoExpirar}
          />
        </Painel>
      )}
      {apagando !== null && (
        <Painel titulo={`Apagar ${apagando.nome}`} aoFechar={() => setApagando(null)}>
          <ApagarConta
            conta={apagando}
            aoApagar={() => {
              setEstado((atual) =>
                atual.tipo === 'ok' ? { tipo: 'ok', contas: atual.contas.filter((outra) => outra.id !== apagando.id) } : atual,
              )
              if (faturasDe === apagando.id) setFaturasDe(null)
              setApagando(null)
              aoMudarSaldo()
            }}
            aoNaoExistir={fecharApagarSumida}
            aoSessaoExpirar={aoSessaoExpirar}
          />
        </Painel>
      )}
      {categoriasDe !== null && !arquivadas && (
        <Painel titulo={`Categorias de ${categoriasDe.nome}`} aoFechar={() => setCategoriasDe(null)}>
          <EditarCategorias
            conta={categoriasDe}
            categorias={categorias}
            aoTentarDeNovo={recarregarCategorias}
            aoSalvar={(salva) => {
              setEstado((atual) =>
                atual.tipo === 'ok'
                  ? { tipo: 'ok', contas: atual.contas.map((outra) => (outra.id === salva.id ? salva : outra)) }
                  : atual,
              )
              setCategoriasDe(null)
            }}
            aoNaoExistir={() => {
              setCategoriasDe(null)
              setFalhaDeAcao('Essa conta não existe mais.')
              recarregar()
            }}
            aoSessaoExpirar={aoSessaoExpirar}
          />
        </Painel>
      )}
    </section>
  )
}
