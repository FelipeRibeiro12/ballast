import type { components } from './api/schema'

export type Healthz = components['schemas']['HealthResponse']

export class ApiError extends Error {}

const baseUrl: string = import.meta.env.VITE_API_URL ?? ''

function isHealthz(valor: unknown): valor is Healthz {
  return (
    typeof valor === 'object' &&
    valor !== null &&
    'mensagem' in valor &&
    typeof valor.mensagem === 'string'
  )
}

export async function fetchHealthz(): Promise<Healthz> {
  const resposta = await fetch(`${baseUrl}/healthz`)
  if (!resposta.ok) {
    throw new ApiError(`healthz respondeu ${resposta.status}`)
  }
  const corpo: unknown = await resposta.json()
  if (!isHealthz(corpo)) {
    throw new ApiError('healthz com formato inesperado')
  }
  return corpo
}

export type Usuario = components['schemas']['Usuario']
export type LoginRequest = components['schemas']['LoginRequest']
export type CadastroRequest = components['schemas']['CadastroRequest']

export class CredenciaisInvalidasError extends ApiError {}

export class LimiteTentativasError extends ApiError {
  // null quando o Retry-After veio ausente ou fora do contrato (inteiro >= 1).
  readonly segundos: number | null

  constructor(segundos: number | null) {
    super('login recusado por excesso de tentativas')
    this.segundos = segundos
  }
}

export class EmailJaCadastradoError extends ApiError {}

export class EntradaInvalidaError extends ApiError {
  // null quando o corpo do 400 não segue o contrato ({ erro: string }).
  readonly mensagemServidor: string | null

  constructor(mensagemServidor: string | null) {
    super('cadastro recusado por entrada inválida')
    this.mensagemServidor = mensagemServidor
  }
}

function isUsuario(valor: unknown): valor is Usuario {
  return (
    typeof valor === 'object' &&
    valor !== null &&
    'id' in valor &&
    typeof valor.id === 'string' &&
    'email' in valor &&
    typeof valor.email === 'string' &&
    'nome' in valor &&
    (valor.nome === null || typeof valor.nome === 'string')
  )
}

function lerRetryAfter(resposta: Response): number | null {
  const bruto = resposta.headers.get('Retry-After')
  if (bruto === null || !/^\d+$/.test(bruto)) return null
  const segundos = Number(bruto)
  return segundos >= 1 ? segundos : null
}

async function lerUsuario(resposta: Response, origem: string): Promise<Usuario> {
  const corpo: unknown = await resposta.json()
  if (!isUsuario(corpo)) {
    throw new ApiError(`${origem} com formato inesperado`)
  }
  return corpo
}

// 401 não é erro aqui: é "ninguém logado".
export async function fetchUsuarioAtual(): Promise<Usuario | null> {
  const resposta = await fetch(`${baseUrl}/auth/eu`, {
    credentials: 'same-origin',
  })
  if (resposta.status === 401) return null
  if (!resposta.ok) {
    throw new ApiError(`/auth/eu respondeu ${resposta.status}`)
  }
  return lerUsuario(resposta, '/auth/eu')
}

export async function postLogin(dados: LoginRequest): Promise<Usuario> {
  const resposta = await fetch(`${baseUrl}/auth/login`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(dados),
  })
  if (resposta.status === 401) throw new CredenciaisInvalidasError('credenciais inválidas')
  if (resposta.status === 429) throw new LimiteTentativasError(lerRetryAfter(resposta))
  if (!resposta.ok) {
    throw new ApiError(`/auth/login respondeu ${resposta.status}`)
  }
  return lerUsuario(resposta, '/auth/login')
}

async function lerMensagemDeErro(resposta: Response): Promise<string | null> {
  try {
    const corpo: unknown = await resposta.json()
    if (
      typeof corpo === 'object' &&
      corpo !== null &&
      'erro' in corpo &&
      typeof corpo.erro === 'string' &&
      corpo.erro !== ''
    ) {
      return corpo.erro
    }
  } catch {
    // Corpo vazio ou que não é JSON: cai na mensagem genérica da tela.
  }
  return null
}

export async function postCadastro(dados: CadastroRequest): Promise<Usuario> {
  const resposta = await fetch(`${baseUrl}/auth/cadastro`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(dados),
  })
  if (resposta.status === 409) throw new EmailJaCadastradoError('e-mail já cadastrado')
  if (resposta.status === 400) throw new EntradaInvalidaError(await lerMensagemDeErro(resposta))
  if (!resposta.ok) {
    throw new ApiError(`/auth/cadastro respondeu ${resposta.status}`)
  }
  return lerUsuario(resposta, '/auth/cadastro')
}

export async function postLogout(): Promise<void> {
  const resposta = await fetch(`${baseUrl}/auth/logout`, {
    method: 'POST',
    credentials: 'same-origin',
  })
  if (resposta.status !== 204) {
    throw new ApiError(`/auth/logout respondeu ${resposta.status}`)
  }
}

export type Conta = components['schemas']['Conta']
export type CriarContaRequest = components['schemas']['CriarContaRequest']

export class SessaoExpiradaError extends ApiError {}

export class NomeDeContaRepetidoError extends ApiError {}

export class ServicoIndisponivelError extends ApiError {}

export class ContaNaoEncontradaError extends ApiError {}

export class ContaInvalidaError extends ApiError {
  // null quando o corpo do 400 não segue o contrato ({ erro: string }).
  readonly mensagemServidor: string | null

  constructor(mensagemServidor: string | null) {
    super('conta recusada por entrada inválida')
    this.mensagemServidor = mensagemServidor
  }
}

const TIPOS_DE_CONTA: readonly string[] = ['corrente', 'poupanca', 'dinheiro']

function isConta(valor: unknown): valor is Conta {
  return (
    typeof valor === 'object' &&
    valor !== null &&
    'id' in valor &&
    typeof valor.id === 'string' &&
    'nome' in valor &&
    typeof valor.nome === 'string' &&
    'tipo' in valor &&
    typeof valor.tipo === 'string' &&
    TIPOS_DE_CONTA.includes(valor.tipo) &&
    'moeda' in valor &&
    valor.moeda === 'BRL' &&
    // Fora do inteiro seguro o JSON já perdeu o centavo no parse.
    'saldoInicialCentavos' in valor &&
    Number.isSafeInteger(valor.saldoInicialCentavos) &&
    'saldoCentavos' in valor &&
    Number.isSafeInteger(valor.saldoCentavos) &&
    'arquivada' in valor &&
    typeof valor.arquivada === 'boolean' &&
    'criadaEm' in valor &&
    typeof valor.criadaEm === 'string'
  )
}

async function lerConta(resposta: Response, origem: string): Promise<Conta> {
  const corpo: unknown = await resposta.json()
  if (!isConta(corpo)) {
    throw new ApiError(`${origem} com formato inesperado`)
  }
  return corpo
}

// Erros comuns às rotas de contas; o 409 e o 400 só existem nas que gravam.
async function recusarResposta(resposta: Response, origem: string): Promise<never> {
  if (resposta.status === 401) throw new SessaoExpiradaError('sessão expirada')
  if (resposta.status === 503) throw new ServicoIndisponivelError('serviço indisponível')
  if (resposta.status === 404) throw new ContaNaoEncontradaError('conta não encontrada')
  if (resposta.status === 409) throw new NomeDeContaRepetidoError('nome de conta repetido')
  if (resposta.status === 400) throw new ContaInvalidaError(await lerMensagemDeErro(resposta))
  throw new ApiError(`${origem} respondeu ${resposta.status}`)
}

export async function fetchContas(arquivadas: boolean): Promise<Conta[]> {
  const resposta = await fetch(`${baseUrl}/contas${arquivadas ? '?arquivadas=true' : ''}`, {
    credentials: 'same-origin',
  })
  if (!resposta.ok) return recusarResposta(resposta, '/contas')
  const corpo: unknown = await resposta.json()
  if (!Array.isArray(corpo) || !corpo.every(isConta)) {
    throw new ApiError('/contas com formato inesperado')
  }
  return corpo
}

export async function postConta(dados: CriarContaRequest): Promise<Conta> {
  const resposta = await fetch(`${baseUrl}/contas`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(dados),
  })
  if (!resposta.ok) return recusarResposta(resposta, 'POST /contas')
  return lerConta(resposta, 'POST /contas')
}

export async function patchConta(id: string, arquivada: boolean): Promise<Conta> {
  const resposta = await fetch(`${baseUrl}/contas/${encodeURIComponent(id)}`, {
    method: 'PATCH',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ arquivada }),
  })
  if (!resposta.ok) return recusarResposta(resposta, 'PATCH /contas/{id}')
  return lerConta(resposta, 'PATCH /contas/{id}')
}

export type Categoria = components['schemas']['Categoria']
export type Lancamento = components['schemas']['Lancamento']
export type LancamentoRequest = components['schemas']['LancamentoRequest']

export class LancamentoNaoEncontradoError extends ApiError {}

export class LancamentoInvalidoError extends ApiError {
  // null quando o corpo do 400 não segue o contrato ({ erro: string }).
  readonly mensagemServidor: string | null

  constructor(mensagemServidor: string | null) {
    super('lançamento recusado por entrada inválida')
    this.mensagemServidor = mensagemServidor
  }
}

function isCategoria(valor: unknown): valor is Categoria {
  return (
    typeof valor === 'object' &&
    valor !== null &&
    'id' in valor &&
    typeof valor.id === 'string' &&
    'nome' in valor &&
    typeof valor.nome === 'string' &&
    'tipo' in valor &&
    (valor.tipo === 'receita' || valor.tipo === 'despesa') &&
    'paiId' in valor &&
    (valor.paiId === null || typeof valor.paiId === 'string') &&
    'cor' in valor &&
    (valor.cor === null || typeof valor.cor === 'string') &&
    'icone' in valor &&
    (valor.icone === null || typeof valor.icone === 'string')
  )
}

// Perna de transferência não tem categoria e sempre traz a ligação e o lado.
// Nos outros tipos é o contrário; qualquer mistura é corpo fora do contrato.
function ligacaoCoerenteComOTipo(
  tipo: unknown,
  categoriaId: unknown,
  transferenciaId: unknown,
  direcao: unknown,
): boolean {
  if (tipo === 'transferencia') {
    return (
      categoriaId === null &&
      typeof transferenciaId === 'string' &&
      (direcao === 'saida' || direcao === 'entrada')
    )
  }
  return typeof categoriaId === 'string' && transferenciaId === null && direcao === null
}

function isLancamento(valor: unknown): valor is Lancamento {
  return (
    typeof valor === 'object' &&
    valor !== null &&
    'id' in valor &&
    typeof valor.id === 'string' &&
    'tipo' in valor &&
    (valor.tipo === 'receita' || valor.tipo === 'despesa' || valor.tipo === 'transferencia') &&
    'status' in valor &&
    valor.status === 'realizado' &&
    'titulo' in valor &&
    typeof valor.titulo === 'string' &&
    'descricao' in valor &&
    (valor.descricao === null || typeof valor.descricao === 'string') &&
    // Fora do inteiro seguro o JSON já perdeu o centavo no parse.
    'valorCentavos' in valor &&
    Number.isSafeInteger(valor.valorCentavos) &&
    'categoriaId' in valor &&
    'transferenciaId' in valor &&
    'direcao' in valor &&
    ligacaoCoerenteComOTipo(valor.tipo, valor.categoriaId, valor.transferenciaId, valor.direcao) &&
    'contaId' in valor &&
    typeof valor.contaId === 'string' &&
    'dataCompetencia' in valor &&
    typeof valor.dataCompetencia === 'string' &&
    'dataCaixa' in valor &&
    typeof valor.dataCaixa === 'string' &&
    'criadoEm' in valor &&
    typeof valor.criadoEm === 'string'
  )
}

async function lerLancamento(resposta: Response, origem: string): Promise<Lancamento> {
  const corpo: unknown = await resposta.json()
  if (!isLancamento(corpo)) {
    throw new ApiError(`${origem} com formato inesperado`)
  }
  return corpo
}

async function recusarRespostaDeLancamento(resposta: Response, origem: string): Promise<never> {
  if (resposta.status === 401) throw new SessaoExpiradaError('sessão expirada')
  if (resposta.status === 503) throw new ServicoIndisponivelError('serviço indisponível')
  if (resposta.status === 404) throw new LancamentoNaoEncontradoError('lançamento não encontrado')
  if (resposta.status === 400) {
    throw new LancamentoInvalidoError(await lerMensagemDeErro(resposta))
  }
  throw new ApiError(`${origem} respondeu ${resposta.status}`)
}

export async function fetchCategorias(): Promise<Categoria[]> {
  const resposta = await fetch(`${baseUrl}/categorias`, { credentials: 'same-origin' })
  if (!resposta.ok) return recusarRespostaDeLancamento(resposta, '/categorias')
  const corpo: unknown = await resposta.json()
  if (!Array.isArray(corpo) || !corpo.every(isCategoria)) {
    throw new ApiError('/categorias com formato inesperado')
  }
  return corpo
}

export async function fetchLancamentos(
  de: string,
  ate: string,
  contaId?: string,
): Promise<Lancamento[]> {
  const parametros = new URLSearchParams({ de, ate })
  if (contaId !== undefined) parametros.set('contaId', contaId)
  const resposta = await fetch(`${baseUrl}/lancamentos?${parametros.toString()}`, {
    credentials: 'same-origin',
  })
  if (!resposta.ok) return recusarRespostaDeLancamento(resposta, '/lancamentos')
  const corpo: unknown = await resposta.json()
  if (!Array.isArray(corpo) || !corpo.every(isLancamento)) {
    throw new ApiError('/lancamentos com formato inesperado')
  }
  return corpo
}

export async function postLancamento(dados: LancamentoRequest): Promise<Lancamento> {
  const resposta = await fetch(`${baseUrl}/lancamentos`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(dados),
  })
  if (!resposta.ok) return recusarRespostaDeLancamento(resposta, 'POST /lancamentos')
  return lerLancamento(resposta, 'POST /lancamentos')
}

export async function putLancamento(id: string, dados: LancamentoRequest): Promise<Lancamento> {
  const resposta = await fetch(`${baseUrl}/lancamentos/${encodeURIComponent(id)}`, {
    method: 'PUT',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(dados),
  })
  if (!resposta.ok) return recusarRespostaDeLancamento(resposta, 'PUT /lancamentos/{id}')
  return lerLancamento(resposta, 'PUT /lancamentos/{id}')
}

export async function deleteLancamento(id: string): Promise<void> {
  const resposta = await fetch(`${baseUrl}/lancamentos/${encodeURIComponent(id)}`, {
    method: 'DELETE',
    credentials: 'same-origin',
  })
  if (resposta.status !== 204) {
    return recusarRespostaDeLancamento(resposta, 'DELETE /lancamentos/{id}')
  }
}

export type Transferencia = components['schemas']['Transferencia']
export type TransferenciaRequest = components['schemas']['TransferenciaRequest']

export class TransferenciaNaoEncontradaError extends ApiError {}

export class TransferenciaInvalidaError extends ApiError {
  // null quando o corpo do 400 não segue o contrato ({ erro: string }).
  readonly mensagemServidor: string | null

  constructor(mensagemServidor: string | null) {
    super('transferência recusada por entrada inválida')
    this.mensagemServidor = mensagemServidor
  }
}

function isTransferencia(valor: unknown): valor is Transferencia {
  return (
    typeof valor === 'object' &&
    valor !== null &&
    'id' in valor &&
    typeof valor.id === 'string' &&
    'contaOrigemId' in valor &&
    typeof valor.contaOrigemId === 'string' &&
    'contaDestinoId' in valor &&
    typeof valor.contaDestinoId === 'string' &&
    // Fora do inteiro seguro o JSON já perdeu o centavo no parse.
    'valorCentavos' in valor &&
    Number.isSafeInteger(valor.valorCentavos) &&
    'data' in valor &&
    typeof valor.data === 'string' &&
    'titulo' in valor &&
    typeof valor.titulo === 'string' &&
    'descricao' in valor &&
    (valor.descricao === null || typeof valor.descricao === 'string') &&
    'saidaId' in valor &&
    typeof valor.saidaId === 'string' &&
    'entradaId' in valor &&
    typeof valor.entradaId === 'string' &&
    'criadaEm' in valor &&
    typeof valor.criadaEm === 'string'
  )
}

async function lerTransferencia(resposta: Response, origem: string): Promise<Transferencia> {
  const corpo: unknown = await resposta.json()
  if (!isTransferencia(corpo)) {
    throw new ApiError(`${origem} com formato inesperado`)
  }
  return corpo
}

async function recusarRespostaDeTransferencia(resposta: Response, origem: string): Promise<never> {
  if (resposta.status === 401) throw new SessaoExpiradaError('sessão expirada')
  if (resposta.status === 503) throw new ServicoIndisponivelError('serviço indisponível')
  if (resposta.status === 404) throw new TransferenciaNaoEncontradaError('transferência não encontrada')
  if (resposta.status === 400) {
    throw new TransferenciaInvalidaError(await lerMensagemDeErro(resposta))
  }
  throw new ApiError(`${origem} respondeu ${resposta.status}`)
}

export async function postTransferencia(dados: TransferenciaRequest): Promise<Transferencia> {
  const resposta = await fetch(`${baseUrl}/transferencias`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(dados),
  })
  if (!resposta.ok) return recusarRespostaDeTransferencia(resposta, 'POST /transferencias')
  return lerTransferencia(resposta, 'POST /transferencias')
}

export async function fetchTransferencia(id: string): Promise<Transferencia> {
  const resposta = await fetch(`${baseUrl}/transferencias/${encodeURIComponent(id)}`, {
    credentials: 'same-origin',
  })
  if (!resposta.ok) return recusarRespostaDeTransferencia(resposta, 'GET /transferencias/{id}')
  return lerTransferencia(resposta, 'GET /transferencias/{id}')
}

export async function deleteTransferencia(id: string): Promise<void> {
  const resposta = await fetch(`${baseUrl}/transferencias/${encodeURIComponent(id)}`, {
    method: 'DELETE',
    credentials: 'same-origin',
  })
  if (resposta.status !== 204) {
    return recusarRespostaDeTransferencia(resposta, 'DELETE /transferencias/{id}')
  }
}
