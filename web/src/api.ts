import type { components } from './api/schema'
import { baseDaApi } from './rotasDaApi'

export type Healthz = components['schemas']['HealthResponse']

export class ApiError extends Error {}

const baseUrl: string = baseDaApi(import.meta.env)

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

// Senha certa com MFA ligado: o login espera o código em postLoginMfa (ADR-050).
export class MfaPendenteError extends ApiError {}

// 400, 401 ou 409 das rotas de MFA, com a mensagem fixa do servidor ("código inválido", "senha
// incorreta", "desafio expirado ou inválido"...).
export class MfaRecusadoError extends ApiError {
  readonly mensagemServidor: string | null

  constructor(mensagemServidor: string | null) {
    super('mfa recusado')
    this.mensagemServidor = mensagemServidor
  }
}

// Teto de usuários do tier gratuito atingido (ADR-049).
export class CadastroEsgotadoError extends ApiError {}

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
  if (resposta.status === 202) throw new MfaPendenteError('código do mfa pendente')
  if (!resposta.ok) {
    throw new ApiError(`/auth/login respondeu ${resposta.status}`)
  }
  return lerUsuario(resposta, '/auth/login')
}

// O desafio vai no cookie HttpOnly que o 202 do login deixou; o JavaScript nunca o vê.
export async function postLoginMfa(codigo: string): Promise<Usuario> {
  const resposta = await fetch(`${baseUrl}/auth/login/mfa`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ codigo }),
  })
  if (resposta.status === 429) throw new LimiteTentativasError(lerRetryAfter(resposta))
  if (resposta.status === 400 || resposta.status === 401) {
    throw new MfaRecusadoError(await lerMensagemDeErro(resposta))
  }
  if (!resposta.ok) {
    throw new ApiError(`/auth/login/mfa respondeu ${resposta.status}`)
  }
  return lerUsuario(resposta, '/auth/login/mfa')
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
  if (resposta.status === 403) throw new CadastroEsgotadoError('limite de usuários atingido')
  if (resposta.status === 429) throw new LimiteTentativasError(lerRetryAfter(resposta))
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

const TIPOS_DE_CONTA: readonly string[] = [
  'corrente',
  'poupanca',
  'dinheiro',
  'cartao_credito',
  'beneficio',
  'cripto',
  'investimento',
]

function isDiaDoMes(valor: unknown): boolean {
  return Number.isSafeInteger(valor) && (valor as number) >= 1 && (valor as number) <= 31
}

function isCartao(valor: unknown): boolean {
  return (
    typeof valor === 'object' &&
    valor !== null &&
    'limiteCentavos' in valor &&
    Number.isSafeInteger(valor.limiteCentavos) &&
    'disponivelCentavos' in valor &&
    Number.isSafeInteger(valor.disponivelCentavos) &&
    'diaFechamento' in valor &&
    isDiaDoMes(valor.diaFechamento) &&
    'diaVencimento' in valor &&
    isDiaDoMes(valor.diaVencimento) &&
    'contaPagamentoId' in valor &&
    typeof valor.contaPagamentoId === 'string'
  )
}

// O servidor sempre manda a chave: objeto só em cartão de crédito, null nos outros tipos.
function cartaoCoerenteComOTipo(tipo: unknown, cartao: unknown): boolean {
  return tipo === 'cartao_credito' ? isCartao(cartao) : cartao === null
}

// Lista (vazia quando tudo é aceito) só no benefício; null nos outros tipos.
function categoriasCoerentesComOTipo(tipo: unknown, categorias: unknown): boolean {
  return tipo === 'beneficio'
    ? Array.isArray(categorias) && categorias.every((id) => typeof id === 'string')
    : categorias === null
}

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
    typeof valor.criadaEm === 'string' &&
    'cartao' in valor &&
    cartaoCoerenteComOTipo(valor.tipo, valor.cartao) &&
    'categoriasPermitidas' in valor &&
    categoriasCoerentesComOTipo(valor.tipo, valor.categoriasPermitidas)
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

export type AtualizarContaRequest = components['schemas']['AtualizarContaRequest']
export type ResumoExclusaoConta = components['schemas']['ResumoExclusaoConta']

// Campo ausente não muda (ADR-036).
export async function atualizarConta(id: string, dados: AtualizarContaRequest): Promise<Conta> {
  const resposta = await fetch(`${baseUrl}/contas/${encodeURIComponent(id)}`, {
    method: 'PATCH',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(dados),
  })
  if (!resposta.ok) return recusarResposta(resposta, 'PATCH /contas/{id}')
  return lerConta(resposta, 'PATCH /contas/{id}')
}

function isResumoExclusao(valor: unknown): valor is ResumoExclusaoConta {
  return (
    typeof valor === 'object' &&
    valor !== null &&
    'lancamentos' in valor &&
    Number.isSafeInteger(valor.lancamentos) &&
    'transferencias' in valor &&
    Number.isSafeInteger(valor.transferencias) &&
    'recorrencias' in valor &&
    Number.isSafeInteger(valor.recorrencias) &&
    'movimentosCripto' in valor &&
    Number.isSafeInteger(valor.movimentosCripto) &&
    'titulosRendaFixa' in valor &&
    Number.isSafeInteger(valor.titulosRendaFixa)
  )
}

export async function fetchResumoExclusao(id: string): Promise<ResumoExclusaoConta> {
  const resposta = await fetch(`${baseUrl}/contas/${encodeURIComponent(id)}/resumo-exclusao`, {
    credentials: 'same-origin',
  })
  if (!resposta.ok) return recusarResposta(resposta, 'GET /contas/{id}/resumo-exclusao')
  const corpo: unknown = await resposta.json()
  if (!isResumoExclusao(corpo)) throw new ApiError('/contas/{id}/resumo-exclusao com formato inesperado')
  return corpo
}

export async function deleteConta(id: string): Promise<void> {
  const resposta = await fetch(`${baseUrl}/contas/${encodeURIComponent(id)}`, {
    method: 'DELETE',
    credentials: 'same-origin',
  })
  if (resposta.status !== 204) return recusarResposta(resposta, 'DELETE /contas/{id}')
}

// Troca a lista inteira; vazia libera qualquer categoria de despesa.
export async function putCategoriasPermitidas(contaId: string, categoriaIds: string[]): Promise<Conta> {
  const resposta = await fetch(`${baseUrl}/contas/${encodeURIComponent(contaId)}/categorias-permitidas`, {
    method: 'PUT',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ categoriaIds }),
  })
  if (!resposta.ok) return recusarResposta(resposta, 'PUT /contas/{id}/categorias-permitidas')
  return lerConta(resposta, 'PUT /contas/{id}/categorias-permitidas')
}

export type Fatura = components['schemas']['Fatura']
export type Faturas = components['schemas']['Faturas']

const SITUACOES_DE_FATURA: readonly string[] = ['aberta', 'fechada', 'paga']

function isFatura(valor: unknown): valor is Fatura {
  return (
    typeof valor === 'object' &&
    valor !== null &&
    'vencimento' in valor &&
    /^\d{4}-\d{2}-\d{2}$/.test(String(valor.vencimento)) &&
    'fechamento' in valor &&
    /^\d{4}-\d{2}-\d{2}$/.test(String(valor.fechamento)) &&
    // Total e restante podem ser negativos (estorno, pagamento a mais); o que importa é o inteiro seguro.
    'totalCentavos' in valor &&
    Number.isSafeInteger(valor.totalCentavos) &&
    'pagoCentavos' in valor &&
    Number.isSafeInteger(valor.pagoCentavos) &&
    'restanteCentavos' in valor &&
    Number.isSafeInteger(valor.restanteCentavos) &&
    'situacao' in valor &&
    typeof valor.situacao === 'string' &&
    SITUACOES_DE_FATURA.includes(valor.situacao)
  )
}

function isFaturas(valor: unknown): valor is Faturas {
  return (
    typeof valor === 'object' &&
    valor !== null &&
    'contaId' in valor &&
    typeof valor.contaId === 'string' &&
    'faturas' in valor &&
    Array.isArray(valor.faturas) &&
    valor.faturas.every(isFatura)
  )
}

// de e ate são datas de vencimento, AAAA-MM-DD.
export async function fetchFaturas(contaId: string, de: string, ate: string): Promise<Faturas> {
  const parametros = new URLSearchParams({ de, ate })
  const resposta = await fetch(`${baseUrl}/contas/${encodeURIComponent(contaId)}/faturas?${parametros.toString()}`, {
    credentials: 'same-origin',
  })
  if (!resposta.ok) return recusarResposta(resposta, '/contas/{id}/faturas')
  const corpo: unknown = await resposta.json()
  if (!isFaturas(corpo)) throw new ApiError('/contas/{id}/faturas com formato inesperado')
  return corpo
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

// Só a ocorrência de um template com divisão tem ordem, de 1 a 10; o resto vem null.
function parcelaOrdemCoerente(parcelaOrdem: unknown, templateId: unknown): boolean {
  if (parcelaOrdem === null) return true
  return templateId !== null && Number.isInteger(parcelaOrdem) && Number(parcelaOrdem) >= 1 && Number(parcelaOrdem) <= 10
}

// Os três campos da compra parcelada vêm todos nulos ou todos preenchidos (ADR-032).
// Só despesa vira parcela, e o número vai de 1 ao total, que fica entre 2 e 48.
function parcelaDeCompraCoerente(tipo: unknown, compraId: unknown, num: unknown, total: unknown): boolean {
  if (compraId === null && num === null && total === null) return true
  return (
    tipo === 'despesa' &&
    typeof compraId === 'string' &&
    Number.isInteger(num) &&
    Number.isInteger(total) &&
    Number(total) >= 2 &&
    Number(total) <= 48 &&
    Number(num) >= 1 &&
    Number(num) <= Number(total)
  )
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
    (valor.status === 'previsto' || valor.status === 'realizado' || valor.status === 'cancelado') &&
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
    'templateId' in valor &&
    (valor.templateId === null || typeof valor.templateId === 'string') &&
    'parcelaOrdem' in valor &&
    parcelaOrdemCoerente(valor.parcelaOrdem, valor.templateId) &&
    'compraParceladaId' in valor &&
    'parcelaNum' in valor &&
    'parcelaTotal' in valor &&
    parcelaDeCompraCoerente(valor.tipo, valor.compraParceladaId, valor.parcelaNum, valor.parcelaTotal) &&
    // Só a regra gera previsto e só ela dá template; a perna é sempre um fato avulso.
    (valor.tipo !== 'transferencia' || (valor.status === 'realizado' && valor.templateId === null)) &&
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

export type CompraParcelada = components['schemas']['CompraParcelada']
export type CompraParceladaItem = components['schemas']['CompraParceladaItem']
export type CompraParceladaRequest = components['schemas']['CompraParceladaRequest']
export type CompraParceladaAtualizarRequest = components['schemas']['CompraParceladaAtualizarRequest']

export class CompraParceladaNaoEncontradaError extends ApiError {}

export class CompraParceladaInvalidaError extends ApiError {
  // null quando o corpo do 400 não segue o contrato ({ erro: string }).
  readonly mensagemServidor: string | null

  constructor(mensagemServidor: string | null) {
    super('compra parcelada recusada por entrada inválida')
    this.mensagemServidor = mensagemServidor
  }
}

function isCompraParceladaItem(valor: unknown): valor is CompraParceladaItem {
  return (
    typeof valor === 'object' &&
    valor !== null &&
    'lancamentoId' in valor &&
    typeof valor.lancamentoId === 'string' &&
    'parcelaNum' in valor &&
    Number.isInteger(valor.parcelaNum) &&
    // Fora do inteiro seguro o JSON já perdeu o centavo no parse.
    'valorCentavos' in valor &&
    Number.isSafeInteger(valor.valorCentavos) &&
    'dataCaixa' in valor &&
    eDataIso(valor.dataCaixa)
  )
}

function isCompraParcelada(valor: unknown): valor is CompraParcelada {
  return (
    typeof valor === 'object' &&
    valor !== null &&
    'id' in valor &&
    typeof valor.id === 'string' &&
    'titulo' in valor &&
    typeof valor.titulo === 'string' &&
    'descricao' in valor &&
    (valor.descricao === null || typeof valor.descricao === 'string') &&
    'valorTotalCentavos' in valor &&
    Number.isSafeInteger(valor.valorTotalCentavos) &&
    'parcelas' in valor &&
    Number.isInteger(valor.parcelas) &&
    Number(valor.parcelas) >= 2 &&
    Number(valor.parcelas) <= 48 &&
    'categoriaId' in valor &&
    typeof valor.categoriaId === 'string' &&
    'contaId' in valor &&
    typeof valor.contaId === 'string' &&
    'dataCompra' in valor &&
    eDataIso(valor.dataCompra) &&
    'criadaEm' in valor &&
    typeof valor.criadaEm === 'string' &&
    'itens' in valor &&
    Array.isArray(valor.itens) &&
    valor.itens.length > 0 &&
    valor.itens.every(isCompraParceladaItem)
  )
}

async function lerCompraParcelada(resposta: Response, origem: string): Promise<CompraParcelada> {
  const corpo: unknown = await resposta.json()
  if (!isCompraParcelada(corpo)) throw new ApiError(`${origem} com formato inesperado`)
  return corpo
}

async function recusarRespostaDeCompra(resposta: Response, origem: string): Promise<never> {
  if (resposta.status === 401) throw new SessaoExpiradaError('sessão expirada')
  if (resposta.status === 503) throw new ServicoIndisponivelError('serviço indisponível')
  if (resposta.status === 404) throw new CompraParceladaNaoEncontradaError('compra parcelada não encontrada')
  if (resposta.status === 400) throw new CompraParceladaInvalidaError(await lerMensagemDeErro(resposta))
  throw new ApiError(`${origem} respondeu ${resposta.status}`)
}

export async function postCompraParcelada(dados: CompraParceladaRequest): Promise<CompraParcelada> {
  const resposta = await fetch(`${baseUrl}/compras-parceladas`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(dados),
  })
  if (!resposta.ok) return recusarRespostaDeCompra(resposta, 'POST /compras-parceladas')
  return lerCompraParcelada(resposta, 'POST /compras-parceladas')
}

export async function fetchCompraParcelada(id: string): Promise<CompraParcelada> {
  const resposta = await fetch(`${baseUrl}/compras-parceladas/${encodeURIComponent(id)}`, {
    credentials: 'same-origin',
  })
  if (!resposta.ok) return recusarRespostaDeCompra(resposta, 'GET /compras-parceladas/{id}')
  return lerCompraParcelada(resposta, 'GET /compras-parceladas/{id}')
}

export async function patchCompraParcelada(
  id: string,
  dados: CompraParceladaAtualizarRequest,
): Promise<CompraParcelada> {
  const resposta = await fetch(`${baseUrl}/compras-parceladas/${encodeURIComponent(id)}`, {
    method: 'PATCH',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(dados),
  })
  if (!resposta.ok) return recusarRespostaDeCompra(resposta, 'PATCH /compras-parceladas/{id}')
  return lerCompraParcelada(resposta, 'PATCH /compras-parceladas/{id}')
}

export async function deleteCompraParcelada(id: string): Promise<void> {
  const resposta = await fetch(`${baseUrl}/compras-parceladas/${encodeURIComponent(id)}`, {
    method: 'DELETE',
    credentials: 'same-origin',
  })
  if (resposta.status !== 204) {
    return recusarRespostaDeCompra(resposta, 'DELETE /compras-parceladas/{id}')
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

// Valor e data só valem para realizado; a assinatura impede mandar com cancelado.
export type AjusteDeConfirmacao = Pick<
  components['schemas']['LancamentoStatusRequest'],
  'valorCentavos' | 'data'
>

export async function patchStatusLancamento(id: string, status: 'cancelado'): Promise<Lancamento>
export async function patchStatusLancamento(
  id: string,
  status: 'realizado',
  ajuste?: AjusteDeConfirmacao,
): Promise<Lancamento>
export async function patchStatusLancamento(
  id: string,
  status: 'realizado' | 'cancelado',
  ajuste?: AjusteDeConfirmacao,
): Promise<Lancamento> {
  const resposta = await fetch(`${baseUrl}/lancamentos/${encodeURIComponent(id)}`, {
    method: 'PATCH',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ status, ...ajuste }),
  })
  if (!resposta.ok) return recusarRespostaDeLancamento(resposta, 'PATCH /lancamentos/{id}')
  return lerLancamento(resposta, 'PATCH /lancamentos/{id}')
}

export type LancamentosVencidos = components['schemas']['LancamentosVencidos']

function isLancamentosVencidos(valor: unknown): valor is LancamentosVencidos {
  return (
    typeof valor === 'object' &&
    valor !== null &&
    'lancamentos' in valor &&
    Array.isArray(valor.lancamentos) &&
    valor.lancamentos.every(isLancamento) &&
    'maisAlgum' in valor &&
    typeof valor.maisAlgum === 'boolean'
  )
}

export async function fetchVencidos(): Promise<LancamentosVencidos> {
  const resposta = await fetch(`${baseUrl}/lancamentos/vencidos`, { credentials: 'same-origin' })
  if (!resposta.ok) return recusarRespostaDeLancamento(resposta, '/lancamentos/vencidos')
  const corpo: unknown = await resposta.json()
  if (!isLancamentosVencidos(corpo)) throw new ApiError('/lancamentos/vencidos com formato inesperado')
  return corpo
}

export type Recorrencia = components['schemas']['Recorrencia']
export type RegraRecorrencia = components['schemas']['RegraRecorrencia']
export type RecorrenciaRequest = components['schemas']['RecorrenciaRequest']
export type RecorrenciaCriada = components['schemas']['RecorrenciaCriada']

export class RecorrenciaNaoEncontradaError extends ApiError {}

export class RecorrenciaInvalidaError extends ApiError {
  // null quando o corpo do 400 não segue o contrato ({ erro: string }).
  readonly mensagemServidor: string | null

  constructor(mensagemServidor: string | null) {
    super('recorrência recusada por entrada inválida')
    this.mensagemServidor = mensagemServidor
  }
}

function inteiroOuNulo(valor: unknown): boolean {
  return valor === null || Number.isSafeInteger(valor)
}

function isRegraRecorrencia(valor: unknown): valor is RegraRecorrencia {
  return (
    typeof valor === 'object' &&
    valor !== null &&
    'frequencia' in valor &&
    (valor.frequencia === 'diaria' ||
      valor.frequencia === 'semanal' ||
      valor.frequencia === 'mensal' ||
      valor.frequencia === 'anual') &&
    'intervalo' in valor &&
    Number.isSafeInteger(valor.intervalo) &&
    'diaDoMes' in valor &&
    inteiroOuNulo(valor.diaDoMes) &&
    'diaDaSemana' in valor &&
    inteiroOuNulo(valor.diaDaSemana) &&
    'inicio' in valor &&
    typeof valor.inicio === 'string' &&
    'fim' in valor &&
    (valor.fim === null || typeof valor.fim === 'string') &&
    'ocorrenciasMax' in valor &&
    inteiroOuNulo(valor.ocorrenciasMax)
  )
}

// Cada parcela traz só o campo do modo da divisão; o do outro modo é recusado.
function isParcelaDivisao(valor: unknown, modo: 'percentual' | 'valor'): boolean {
  if (typeof valor !== 'object' || valor === null) return false
  const temPercentual = 'percentualCentesimos' in valor
  const temValor = 'valorCentavos' in valor
  const campoDoModo = modo === 'percentual' ? (temPercentual ? valor.percentualCentesimos : undefined) : temValor ? valor.valorCentavos : undefined
  return (
    'ordem' in valor &&
    Number.isSafeInteger(valor.ordem) &&
    'diaDoMes' in valor &&
    Number.isSafeInteger(valor.diaDoMes) &&
    Number.isSafeInteger(campoDoModo) &&
    // O campo do outro modo não pode vir, nem nulo.
    !(modo === 'percentual' ? temValor : temPercentual) &&
    'valorParcelaCentavos' in valor &&
    Number.isSafeInteger(valor.valorParcelaCentavos)
  )
}

function isDivisaoRecorrencia(valor: unknown): boolean {
  if (valor === null) return true
  if (typeof valor !== 'object' || !('modo' in valor) || !('parcelas' in valor)) return false
  const { modo, parcelas } = valor
  if (modo !== 'percentual' && modo !== 'valor') return false
  return Array.isArray(parcelas) && parcelas.every((parcela) => isParcelaDivisao(parcela, modo))
}

function isRecorrencia(valor: unknown): valor is Recorrencia {
  return (
    typeof valor === 'object' &&
    valor !== null &&
    'id' in valor &&
    typeof valor.id === 'string' &&
    'tipo' in valor &&
    (valor.tipo === 'receita' || valor.tipo === 'despesa') &&
    'titulo' in valor &&
    typeof valor.titulo === 'string' &&
    'descricao' in valor &&
    (valor.descricao === null || typeof valor.descricao === 'string') &&
    // Fora do inteiro seguro o JSON já perdeu o centavo no parse.
    'valorCentavos' in valor &&
    Number.isSafeInteger(valor.valorCentavos) &&
    'categoriaId' in valor &&
    typeof valor.categoriaId === 'string' &&
    'contaId' in valor &&
    typeof valor.contaId === 'string' &&
    'ativo' in valor &&
    typeof valor.ativo === 'boolean' &&
    'criadoEm' in valor &&
    typeof valor.criadoEm === 'string' &&
    'regra' in valor &&
    isRegraRecorrencia(valor.regra) &&
    'divisao' in valor &&
    isDivisaoRecorrencia(valor.divisao) &&
    'proximaOcorrencia' in valor &&
    (valor.proximaOcorrencia === null || typeof valor.proximaOcorrencia === 'string')
  )
}

function isRecorrenciaCriada(valor: unknown): valor is RecorrenciaCriada {
  return (
    typeof valor === 'object' &&
    valor !== null &&
    'ocorrenciasMaterializadas' in valor &&
    Number.isSafeInteger(valor.ocorrenciasMaterializadas) &&
    'recorrencia' in valor &&
    isRecorrencia(valor.recorrencia)
  )
}

async function lerRecorrencia(resposta: Response, origem: string): Promise<Recorrencia> {
  const corpo: unknown = await resposta.json()
  if (!isRecorrencia(corpo)) {
    throw new ApiError(`${origem} com formato inesperado`)
  }
  return corpo
}

async function recusarRespostaDeRecorrencia(resposta: Response, origem: string): Promise<never> {
  if (resposta.status === 401) throw new SessaoExpiradaError('sessão expirada')
  if (resposta.status === 503) throw new ServicoIndisponivelError('serviço indisponível')
  if (resposta.status === 404) throw new RecorrenciaNaoEncontradaError('recorrência não encontrada')
  if (resposta.status === 400) {
    throw new RecorrenciaInvalidaError(await lerMensagemDeErro(resposta))
  }
  throw new ApiError(`${origem} respondeu ${resposta.status}`)
}

export async function fetchRecorrencias(): Promise<Recorrencia[]> {
  const resposta = await fetch(`${baseUrl}/recorrencias`, { credentials: 'same-origin' })
  if (!resposta.ok) return recusarRespostaDeRecorrencia(resposta, '/recorrencias')
  const corpo: unknown = await resposta.json()
  if (!Array.isArray(corpo) || !corpo.every(isRecorrencia)) {
    throw new ApiError('/recorrencias com formato inesperado')
  }
  return corpo
}

export async function postRecorrencia(dados: RecorrenciaRequest): Promise<RecorrenciaCriada> {
  const resposta = await fetch(`${baseUrl}/recorrencias`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(dados),
  })
  if (!resposta.ok) return recusarRespostaDeRecorrencia(resposta, 'POST /recorrencias')
  const corpo: unknown = await resposta.json()
  if (!isRecorrenciaCriada(corpo)) {
    throw new ApiError('POST /recorrencias com formato inesperado')
  }
  return corpo
}

// O contrato só aceita ativo false: a recorrência não é reativada.
export async function desativarRecorrencia(id: string): Promise<Recorrencia> {
  const resposta = await fetch(`${baseUrl}/recorrencias/${encodeURIComponent(id)}`, {
    method: 'PATCH',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ ativo: false }),
  })
  if (!resposta.ok) return recusarRespostaDeRecorrencia(resposta, 'PATCH /recorrencias/{id}')
  return lerRecorrencia(resposta, 'PATCH /recorrencias/{id}')
}

export async function reativarRecorrencia(id: string): Promise<Recorrencia> {
  const resposta = await fetch(`${baseUrl}/recorrencias/${encodeURIComponent(id)}`, {
    method: 'PATCH',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ ativo: true }),
  })
  if (!resposta.ok) return recusarRespostaDeRecorrencia(resposta, 'PATCH /recorrencias/{id}')
  return lerRecorrencia(resposta, 'PATCH /recorrencias/{id}')
}

// O corpo é o do POST, com o mesmo tipo; regera as previstas futuras não editadas (ADR-036).
export async function putRecorrencia(id: string, dados: RecorrenciaRequest): Promise<RecorrenciaCriada> {
  const resposta = await fetch(`${baseUrl}/recorrencias/${encodeURIComponent(id)}`, {
    method: 'PUT',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(dados),
  })
  if (!resposta.ok) return recusarRespostaDeRecorrencia(resposta, 'PUT /recorrencias/{id}')
  const corpo: unknown = await resposta.json()
  if (!isRecorrenciaCriada(corpo)) throw new ApiError('PUT /recorrencias/{id} com formato inesperado')
  return corpo
}

export async function deleteRecorrencia(id: string): Promise<void> {
  const resposta = await fetch(`${baseUrl}/recorrencias/${encodeURIComponent(id)}`, {
    method: 'DELETE',
    credentials: 'same-origin',
  })
  if (resposta.status !== 204) return recusarRespostaDeRecorrencia(resposta, 'DELETE /recorrencias/{id}')
}

export type SaldoProjetado = components['schemas']['SaldoProjetado']
export type DiaProjetado = components['schemas']['DiaProjetado']
export type MenorSaldo = components['schemas']['MenorSaldo']

export class ProjecaoInvalidaError extends ApiError {
  // null quando o corpo do 400 não segue o contrato ({ erro: string }).
  readonly mensagemServidor: string | null

  constructor(mensagemServidor: string | null) {
    super('projeção recusada por entrada inválida')
    this.mensagemServidor = mensagemServidor
  }
}

function eDataIso(valor: unknown): boolean {
  return typeof valor === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(valor)
}

function isDiaProjetado(valor: unknown): valor is DiaProjetado {
  return (
    typeof valor === 'object' &&
    valor !== null &&
    'data' in valor &&
    eDataIso(valor.data) &&
    // Fora do inteiro seguro o JSON já perdeu o centavo no parse.
    'entradasCentavos' in valor &&
    Number.isSafeInteger(valor.entradasCentavos) &&
    'saidasCentavos' in valor &&
    Number.isSafeInteger(valor.saidasCentavos) &&
    'saldoCentavos' in valor &&
    Number.isSafeInteger(valor.saldoCentavos)
  )
}

function isMenorSaldo(valor: unknown): valor is MenorSaldo {
  return (
    typeof valor === 'object' &&
    valor !== null &&
    'data' in valor &&
    eDataIso(valor.data) &&
    'saldoCentavos' in valor &&
    Number.isSafeInteger(valor.saldoCentavos)
  )
}

function isSaldoProjetado(valor: unknown): valor is SaldoProjetado {
  return (
    typeof valor === 'object' &&
    valor !== null &&
    'inicio' in valor &&
    eDataIso(valor.inicio) &&
    'fim' in valor &&
    eDataIso(valor.fim) &&
    'contaId' in valor &&
    (valor.contaId === null || typeof valor.contaId === 'string') &&
    'saldoAtualCentavos' in valor &&
    Number.isSafeInteger(valor.saldoAtualCentavos) &&
    'vencidasCentavos' in valor &&
    Number.isSafeInteger(valor.vencidasCentavos) &&
    'menorSaldo' in valor &&
    isMenorSaldo(valor.menorSaldo) &&
    // A série tem ao menos hoje; lista vazia é corpo fora do contrato.
    'dias' in valor &&
    Array.isArray(valor.dias) &&
    valor.dias.length > 0 &&
    valor.dias.every(isDiaProjetado)
  )
}

async function recusarRespostaDeProjecao(resposta: Response, origem: string): Promise<never> {
  if (resposta.status === 401) throw new SessaoExpiradaError('sessão expirada')
  if (resposta.status === 503) throw new ServicoIndisponivelError('serviço indisponível')
  if (resposta.status === 404) throw new ContaNaoEncontradaError('conta não encontrada')
  if (resposta.status === 400) throw new ProjecaoInvalidaError(await lerMensagemDeErro(resposta))
  throw new ApiError(`${origem} respondeu ${resposta.status}`)
}

export async function fetchSaldoProjetado(dias: number, contaId?: string): Promise<SaldoProjetado> {
  const parametros = new URLSearchParams({ dias: String(dias) })
  if (contaId !== undefined) parametros.set('contaId', contaId)
  const resposta = await fetch(`${baseUrl}/saldo-projetado?${parametros.toString()}`, {
    credentials: 'same-origin',
  })
  if (!resposta.ok) return recusarRespostaDeProjecao(resposta, '/saldo-projetado')
  const corpo: unknown = await resposta.json()
  if (!isSaldoProjetado(corpo)) throw new ApiError('/saldo-projetado com formato inesperado')
  return corpo
}

export type AtivoCripto = components['schemas']['AtivoCripto']
export type AtivoCriptoRequest = components['schemas']['AtivoCriptoRequest']
export type CotacaoRequest = components['schemas']['CotacaoRequest']
export type MovimentoCripto = components['schemas']['MovimentoCripto']
export type MovimentoCriptoRequest = components['schemas']['MovimentoCriptoRequest']
export type PosicaoCripto = components['schemas']['PosicaoCripto']
export type PosicoesCripto = components['schemas']['PosicoesCripto']
export type Patrimonio = components['schemas']['Patrimonio']

export class CriptoNaoEncontradoError extends ApiError {}

export class CriptoRepetidoError extends ApiError {
  // null quando o corpo do 409 não segue o contrato ({ erro: string }).
  readonly mensagemServidor: string | null

  constructor(mensagemServidor: string | null) {
    super('cripto recusado por repetição')
    this.mensagemServidor = mensagemServidor
  }
}

export class CriptoInvalidoError extends ApiError {
  // null quando o corpo do 400 não segue o contrato ({ erro: string }).
  readonly mensagemServidor: string | null

  constructor(mensagemServidor: string | null) {
    super('cripto recusado por entrada inválida')
    this.mensagemServidor = mensagemServidor
  }
}

const REDES_CRIPTO: readonly string[] = ['bitcoin', 'ethereum', 'base', 'arbitrum', 'polygon', 'solana']

// Quantidade e preço chegam em texto e continuam texto: Number não guarda 18 casas (ADR-005).
function eDecimalEmTexto(valor: unknown): boolean {
  return typeof valor === 'string' && /^-?\d+(\.\d+)?$/.test(valor)
}

function isAtivoCripto(valor: unknown): valor is AtivoCripto {
  return (
    typeof valor === 'object' &&
    valor !== null &&
    'id' in valor &&
    typeof valor.id === 'string' &&
    'rede' in valor &&
    typeof valor.rede === 'string' &&
    REDES_CRIPTO.includes(valor.rede) &&
    'contrato' in valor &&
    (valor.contrato === null || typeof valor.contrato === 'string') &&
    'simbolo' in valor &&
    typeof valor.simbolo === 'string' &&
    'nome' in valor &&
    (valor.nome === null || typeof valor.nome === 'string') &&
    'decimais' in valor &&
    Number.isInteger(valor.decimais) &&
    'confiavel' in valor &&
    typeof valor.confiavel === 'boolean'
  )
}

function isMovimentoCripto(valor: unknown): valor is MovimentoCripto {
  return (
    typeof valor === 'object' &&
    valor !== null &&
    'id' in valor &&
    typeof valor.id === 'string' &&
    'contaId' in valor &&
    typeof valor.contaId === 'string' &&
    'ativoId' in valor &&
    typeof valor.ativoId === 'string' &&
    'tipo' in valor &&
    (valor.tipo === 'compra' || valor.tipo === 'venda') &&
    'quantidade' in valor &&
    eDecimalEmTexto(valor.quantidade) &&
    'data' in valor &&
    eDataIso(valor.data) &&
    // Custo em dólar e em reais pela PTAX; o convertido é nulo sem PTAX (ADR-038).
    'custoUsdCentavos' in valor &&
    inteiroSeguroOuNulo(valor.custoUsdCentavos) &&
    'custoCentavos' in valor &&
    inteiroSeguroOuNulo(valor.custoCentavos)
  )
}

function isCotacaoDaPosicao(valor: unknown): boolean {
  return (
    typeof valor === 'object' &&
    valor !== null &&
    'precoUsd' in valor &&
    (valor.precoUsd === null || eDecimalEmTexto(valor.precoUsd)) &&
    'momento' in valor &&
    typeof valor.momento === 'string'
  )
}

// Sem cotação, os dois valores são nulos. Com ela há valor em dólar, e o em reais depende da PTAX.
function isPosicaoCripto(valor: unknown): valor is PosicaoCripto {
  if (
    typeof valor !== 'object' ||
    valor === null ||
    !('contaId' in valor) ||
    typeof valor.contaId !== 'string' ||
    !('ativoId' in valor) ||
    typeof valor.ativoId !== 'string' ||
    !('quantidade' in valor) ||
    !eDecimalEmTexto(valor.quantidade) ||
    !('custoUsdCentavos' in valor) ||
    !inteiroSeguroOuNulo(valor.custoUsdCentavos) ||
    !('custoCentavos' in valor) ||
    !inteiroSeguroOuNulo(valor.custoCentavos) ||
    !('cotacao' in valor) ||
    !('valorUsdCentavos' in valor) ||
    !inteiroSeguroOuNulo(valor.valorUsdCentavos) ||
    !('valorCentavos' in valor) ||
    !inteiroSeguroOuNulo(valor.valorCentavos) ||
    // Custo médio, ganho em aberto e realizado nas vendas (ADR-042).
    !('custoMedioUsd' in valor) ||
    !(valor.custoMedioUsd === null || eDecimalEmTexto(valor.custoMedioUsd)) ||
    !('custoDaPosicaoUsdCentavos' in valor) ||
    !inteiroSeguroOuNulo(valor.custoDaPosicaoUsdCentavos) ||
    !('custoDaPosicaoCentavos' in valor) ||
    !inteiroSeguroOuNulo(valor.custoDaPosicaoCentavos) ||
    !('ganhoUsdCentavos' in valor) ||
    !inteiroSeguroOuNulo(valor.ganhoUsdCentavos) ||
    !('ganhoCentavos' in valor) ||
    !inteiroSeguroOuNulo(valor.ganhoCentavos) ||
    !('realizadoUsdCentavos' in valor) ||
    !inteiroSeguroOuNulo(valor.realizadoUsdCentavos) ||
    !('realizadoCentavos' in valor) ||
    !inteiroSeguroOuNulo(valor.realizadoCentavos)
  ) {
    return false
  }
  if (valor.cotacao === null) return valor.valorCentavos === null && valor.valorUsdCentavos === null
  return isCotacaoDaPosicao(valor.cotacao)
}

function isDolar(valor: unknown): boolean {
  return (
    typeof valor === 'object' &&
    valor !== null &&
    'valor' in valor &&
    eDecimalEmTexto(valor.valor) &&
    'data' in valor &&
    eDataIso(valor.data)
  )
}

function isPosicoesCripto(valor: unknown): valor is PosicoesCripto {
  return (
    typeof valor === 'object' &&
    valor !== null &&
    'data' in valor &&
    eDataIso(valor.data) &&
    'dolar' in valor &&
    (valor.dolar === null || isDolar(valor.dolar)) &&
    'posicoes' in valor &&
    Array.isArray(valor.posicoes) &&
    valor.posicoes.every(isPosicaoCripto)
  )
}

function isPosicaoSemCotacao(valor: unknown): boolean {
  return (
    typeof valor === 'object' &&
    valor !== null &&
    'contaId' in valor &&
    typeof valor.contaId === 'string' &&
    'ativoId' in valor &&
    typeof valor.ativoId === 'string' &&
    'quantidade' in valor &&
    eDecimalEmTexto(valor.quantidade)
  )
}

function isPatrimonio(valor: unknown): valor is Patrimonio {
  return (
    typeof valor === 'object' &&
    valor !== null &&
    'data' in valor &&
    eDataIso(valor.data) &&
    'contasCentavos' in valor &&
    Number.isSafeInteger(valor.contasCentavos) &&
    'criptoCentavos' in valor &&
    Number.isSafeInteger(valor.criptoCentavos) &&
    'criptoAportadoCentavos' in valor &&
    inteiroSeguroOuNulo(valor.criptoAportadoCentavos) &&
    'criptoValorizacaoCentavos' in valor &&
    inteiroSeguroOuNulo(valor.criptoValorizacaoCentavos) &&
    'rendaFixaCentavos' in valor &&
    Number.isSafeInteger(valor.rendaFixaCentavos) &&
    'totalCentavos' in valor &&
    Number.isSafeInteger(valor.totalCentavos) &&
    'semCotacao' in valor &&
    Array.isArray(valor.semCotacao) &&
    valor.semCotacao.every(isPosicaoSemCotacao)
  )
}

async function recusarRespostaDeCripto(resposta: Response, origem: string): Promise<never> {
  if (resposta.status === 401) throw new SessaoExpiradaError('sessão expirada')
  if (resposta.status === 503) throw new ServicoIndisponivelError('serviço indisponível')
  if (resposta.status === 404) throw new CriptoNaoEncontradoError('não encontrado')
  if (resposta.status === 409) throw new CriptoRepetidoError(await lerMensagemDeErro(resposta))
  if (resposta.status === 400) throw new CriptoInvalidoError(await lerMensagemDeErro(resposta))
  throw new ApiError(`${origem} respondeu ${resposta.status}`)
}

async function enviarJson(caminho: string, dados: unknown): Promise<Response> {
  return fetch(`${baseUrl}${caminho}`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(dados),
  })
}

export async function fetchAtivosCripto(): Promise<AtivoCripto[]> {
  const resposta = await fetch(`${baseUrl}/ativos-cripto`, { credentials: 'same-origin' })
  if (!resposta.ok) return recusarRespostaDeCripto(resposta, '/ativos-cripto')
  const corpo: unknown = await resposta.json()
  if (!Array.isArray(corpo) || !corpo.every(isAtivoCripto)) {
    throw new ApiError('/ativos-cripto com formato inesperado')
  }
  return corpo
}

export async function postAtivoCripto(dados: AtivoCriptoRequest): Promise<AtivoCripto> {
  const resposta = await enviarJson('/ativos-cripto', dados)
  if (!resposta.ok) return recusarRespostaDeCripto(resposta, 'POST /ativos-cripto')
  const corpo: unknown = await resposta.json()
  if (!isAtivoCripto(corpo)) throw new ApiError('POST /ativos-cripto com formato inesperado')
  return corpo
}

export async function postCotacao(ativoId: string, dados: CotacaoRequest): Promise<void> {
  const resposta = await enviarJson(`/ativos-cripto/${encodeURIComponent(ativoId)}/cotacoes`, dados)
  if (resposta.status !== 201) {
    return recusarRespostaDeCripto(resposta, 'POST /ativos-cripto/{id}/cotacoes')
  }
}

export async function fetchMovimentosCripto(): Promise<MovimentoCripto[]> {
  const resposta = await fetch(`${baseUrl}/movimentos-cripto`, { credentials: 'same-origin' })
  if (!resposta.ok) return recusarRespostaDeCripto(resposta, '/movimentos-cripto')
  const corpo: unknown = await resposta.json()
  if (!Array.isArray(corpo) || !corpo.every(isMovimentoCripto)) {
    throw new ApiError('/movimentos-cripto com formato inesperado')
  }
  return corpo
}

export async function postMovimentoCripto(dados: MovimentoCriptoRequest): Promise<MovimentoCripto> {
  const resposta = await enviarJson('/movimentos-cripto', dados)
  if (!resposta.ok) return recusarRespostaDeCripto(resposta, 'POST /movimentos-cripto')
  const corpo: unknown = await resposta.json()
  if (!isMovimentoCripto(corpo)) throw new ApiError('POST /movimentos-cripto com formato inesperado')
  return corpo
}

export async function deleteMovimentoCripto(id: string): Promise<void> {
  const resposta = await fetch(`${baseUrl}/movimentos-cripto/${encodeURIComponent(id)}`, {
    method: 'DELETE',
    credentials: 'same-origin',
  })
  if (resposta.status !== 204) {
    return recusarRespostaDeCripto(resposta, 'DELETE /movimentos-cripto/{id}')
  }
}

export async function fetchPosicoesCripto(data: string): Promise<PosicoesCripto> {
  const parametros = new URLSearchParams({ data })
  const resposta = await fetch(`${baseUrl}/posicoes-cripto?${parametros.toString()}`, {
    credentials: 'same-origin',
  })
  if (!resposta.ok) return recusarRespostaDeCripto(resposta, '/posicoes-cripto')
  const corpo: unknown = await resposta.json()
  if (!isPosicoesCripto(corpo)) throw new ApiError('/posicoes-cripto com formato inesperado')
  return corpo
}

export async function fetchPatrimonio(data: string): Promise<Patrimonio> {
  const parametros = new URLSearchParams({ data })
  const resposta = await fetch(`${baseUrl}/patrimonio?${parametros.toString()}`, {
    credentials: 'same-origin',
  })
  if (!resposta.ok) return recusarRespostaDeCripto(resposta, '/patrimonio')
  const corpo: unknown = await resposta.json()
  if (!isPatrimonio(corpo)) throw new ApiError('/patrimonio com formato inesperado')
  return corpo
}

export type TituloRendaFixa = components['schemas']['TituloRendaFixa']
export type TitulosRendaFixa = components['schemas']['TitulosRendaFixa']
export type TituloRendaFixaRequest = components['schemas']['TituloRendaFixaRequest']
export type Indices = components['schemas']['Indices']

const TIPOS_DE_TITULO: readonly string[] = ['lci', 'lca', 'cdb']
const INDEXADORES: readonly string[] = ['prefixado', 'cdi', 'ipca']

function inteiroSeguroOuNulo(valor: unknown): boolean {
  return valor === null || Number.isSafeInteger(valor)
}

function isValorNaData(valor: unknown): boolean {
  return (
    typeof valor === 'object' &&
    valor !== null &&
    'data' in valor &&
    eDataIso(valor.data) &&
    'valorCentavos' in valor &&
    Number.isSafeInteger(valor.valorCentavos)
  )
}

function isTituloRendaFixa(valor: unknown): valor is TituloRendaFixa {
  return (
    typeof valor === 'object' &&
    valor !== null &&
    'id' in valor &&
    typeof valor.id === 'string' &&
    'contaId' in valor &&
    typeof valor.contaId === 'string' &&
    'tipo' in valor &&
    typeof valor.tipo === 'string' &&
    TIPOS_DE_TITULO.includes(valor.tipo) &&
    'nome' in valor &&
    typeof valor.nome === 'string' &&
    'valorAplicadoCentavos' in valor &&
    Number.isSafeInteger(valor.valorAplicadoCentavos) &&
    'dataAplicacao' in valor &&
    eDataIso(valor.dataAplicacao) &&
    'vencimento' in valor &&
    (valor.vencimento === null || eDataIso(valor.vencimento)) &&
    'indexador' in valor &&
    typeof valor.indexador === 'string' &&
    INDEXADORES.includes(valor.indexador) &&
    'taxa' in valor &&
    eDecimalEmTexto(valor.taxa) &&
    'resgate' in valor &&
    (valor.resgate === null || isValorNaData(valor.resgate)) &&
    'ultimaMarcacao' in valor &&
    (valor.ultimaMarcacao === null || isValorNaData(valor.ultimaMarcacao)) &&
    'valorBrutoCentavos' in valor &&
    inteiroSeguroOuNulo(valor.valorBrutoCentavos) &&
    'iofCentavos' in valor &&
    inteiroSeguroOuNulo(valor.iofCentavos) &&
    'irCentavos' in valor &&
    inteiroSeguroOuNulo(valor.irCentavos) &&
    'valorLiquidoCentavos' in valor &&
    inteiroSeguroOuNulo(valor.valorLiquidoCentavos) &&
    'rendimentoCentavos' in valor &&
    inteiroSeguroOuNulo(valor.rendimentoCentavos)
  )
}

function isIndices(valor: unknown): valor is Indices {
  return (
    typeof valor === 'object' &&
    valor !== null &&
    'cdiAte' in valor &&
    (valor.cdiAte === null || eDataIso(valor.cdiAte)) &&
    'ipcaAte' in valor &&
    (valor.ipcaAte === null || eDataIso(valor.ipcaAte))
  )
}

function isTitulosRendaFixa(valor: unknown): valor is TitulosRendaFixa {
  return (
    typeof valor === 'object' &&
    valor !== null &&
    'data' in valor &&
    eDataIso(valor.data) &&
    'indices' in valor &&
    isIndices(valor.indices) &&
    'titulos' in valor &&
    Array.isArray(valor.titulos) &&
    valor.titulos.every(isTituloRendaFixa)
  )
}

async function lerTitulo(resposta: Response, origem: string): Promise<TituloRendaFixa> {
  const corpo: unknown = await resposta.json()
  if (!isTituloRendaFixa(corpo)) throw new ApiError(`${origem} com formato inesperado`)
  return corpo
}

// Os erros seguem os de cripto: mesma família de telas e mesmas classes na tela.
export async function fetchTitulosRendaFixa(data: string): Promise<TitulosRendaFixa> {
  const parametros = new URLSearchParams({ data })
  const resposta = await fetch(`${baseUrl}/titulos-renda-fixa?${parametros.toString()}`, {
    credentials: 'same-origin',
  })
  if (!resposta.ok) return recusarRespostaDeCripto(resposta, '/titulos-renda-fixa')
  const corpo: unknown = await resposta.json()
  if (!isTitulosRendaFixa(corpo)) throw new ApiError('/titulos-renda-fixa com formato inesperado')
  return corpo
}

export async function postTituloRendaFixa(dados: TituloRendaFixaRequest): Promise<TituloRendaFixa> {
  const resposta = await enviarJson('/titulos-renda-fixa', dados)
  if (!resposta.ok) return recusarRespostaDeCripto(resposta, 'POST /titulos-renda-fixa')
  return lerTitulo(resposta, 'POST /titulos-renda-fixa')
}

export async function putTituloRendaFixa(id: string, dados: TituloRendaFixaRequest): Promise<TituloRendaFixa> {
  const resposta = await fetch(`${baseUrl}/titulos-renda-fixa/${encodeURIComponent(id)}`, {
    method: 'PUT',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(dados),
  })
  if (!resposta.ok) return recusarRespostaDeCripto(resposta, 'PUT /titulos-renda-fixa/{id}')
  return lerTitulo(resposta, 'PUT /titulos-renda-fixa/{id}')
}

export async function deleteTituloRendaFixa(id: string): Promise<void> {
  const resposta = await fetch(`${baseUrl}/titulos-renda-fixa/${encodeURIComponent(id)}`, {
    method: 'DELETE',
    credentials: 'same-origin',
  })
  if (resposta.status !== 204) return recusarRespostaDeCripto(resposta, 'DELETE /titulos-renda-fixa/{id}')
}

export async function postMarcacao(id: string, data: string, valorCentavos: number): Promise<void> {
  const resposta = await enviarJson(`/titulos-renda-fixa/${encodeURIComponent(id)}/marcacoes`, { data, valorCentavos })
  if (resposta.status !== 201) return recusarRespostaDeCripto(resposta, 'POST /titulos-renda-fixa/{id}/marcacoes')
}

export async function postResgate(id: string, data: string, valorCentavos: number): Promise<TituloRendaFixa> {
  const resposta = await enviarJson(`/titulos-renda-fixa/${encodeURIComponent(id)}/resgate`, { data, valorCentavos })
  if (!resposta.ok) return recusarRespostaDeCripto(resposta, 'POST /titulos-renda-fixa/{id}/resgate')
  return lerTitulo(resposta, 'POST /titulos-renda-fixa/{id}/resgate')
}

export type OrcamentosDoMes = components['schemas']['OrcamentosDoMes']
export type Orcamento = components['schemas']['Orcamento']

export class OrcamentoNaoEncontradoError extends ApiError {}

export class OrcamentoInvalidoError extends ApiError {
  // null quando o corpo do 400 não segue o contrato ({ erro: string }).
  readonly mensagemServidor: string | null

  constructor(mensagemServidor: string | null) {
    super('orçamento recusado por entrada inválida')
    this.mensagemServidor = mensagemServidor
  }
}

function eMesIso(valor: unknown): boolean {
  return typeof valor === 'string' && /^\d{4}-\d{2}$/.test(valor)
}

// Gasto e previsto nunca negativos; o restante fica negativo quando o gasto passa do teto.
function isOrcamento(valor: unknown): valor is Orcamento {
  return (
    typeof valor === 'object' &&
    valor !== null &&
    'categoriaId' in valor &&
    typeof valor.categoriaId === 'string' &&
    'categoriaNome' in valor &&
    typeof valor.categoriaNome === 'string' &&
    'tetoCentavos' in valor &&
    Number.isSafeInteger(valor.tetoCentavos) &&
    'desde' in valor &&
    eMesIso(valor.desde) &&
    'gastoCentavos' in valor &&
    Number.isSafeInteger(valor.gastoCentavos) &&
    'previstoCentavos' in valor &&
    Number.isSafeInteger(valor.previstoCentavos) &&
    'restanteCentavos' in valor &&
    Number.isSafeInteger(valor.restanteCentavos)
  )
}

function isOrcamentosDoMes(valor: unknown): valor is OrcamentosDoMes {
  return (
    typeof valor === 'object' &&
    valor !== null &&
    'mes' in valor &&
    eMesIso(valor.mes) &&
    'orcamentos' in valor &&
    Array.isArray(valor.orcamentos) &&
    valor.orcamentos.every(isOrcamento)
  )
}

async function recusarRespostaDeOrcamento(resposta: Response, origem: string): Promise<never> {
  if (resposta.status === 401) throw new SessaoExpiradaError('sessão expirada')
  if (resposta.status === 503) throw new ServicoIndisponivelError('serviço indisponível')
  if (resposta.status === 404) throw new OrcamentoNaoEncontradoError('categoria não encontrada')
  if (resposta.status === 400) throw new OrcamentoInvalidoError(await lerMensagemDeErro(resposta))
  throw new ApiError(`${origem} respondeu ${resposta.status}`)
}

// mes em AAAA-MM.
export async function fetchOrcamentos(mes: string): Promise<OrcamentosDoMes> {
  const parametros = new URLSearchParams({ mes })
  const resposta = await fetch(`${baseUrl}/orcamentos?${parametros.toString()}`, { credentials: 'same-origin' })
  if (!resposta.ok) return recusarRespostaDeOrcamento(resposta, '/orcamentos')
  const corpo: unknown = await resposta.json()
  if (!isOrcamentosDoMes(corpo)) throw new ApiError('/orcamentos com formato inesperado')
  return corpo
}

export async function putOrcamento(categoriaId: string, tetoCentavos: number, aPartirDe: string): Promise<void> {
  const resposta = await fetch(`${baseUrl}/orcamentos/${encodeURIComponent(categoriaId)}`, {
    method: 'PUT',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ tetoCentavos, aPartirDe }),
  })
  if (resposta.status !== 204) return recusarRespostaDeOrcamento(resposta, 'PUT /orcamentos/{categoriaId}')
}

export async function deleteOrcamento(categoriaId: string, aPartirDe: string): Promise<void> {
  const parametros = new URLSearchParams({ aPartirDe })
  const resposta = await fetch(`${baseUrl}/orcamentos/${encodeURIComponent(categoriaId)}?${parametros.toString()}`, {
    method: 'DELETE',
    credentials: 'same-origin',
  })
  if (resposta.status !== 204) return recusarRespostaDeOrcamento(resposta, 'DELETE /orcamentos/{categoriaId}')
}

export type Meta = components['schemas']['Meta']
export type MetaRequest = components['schemas']['MetaRequest']
export type AporteMeta = components['schemas']['AporteMeta']

export class MetaNaoEncontradaError extends ApiError {}

export class MetaInvalidaError extends ApiError {
  // null quando o corpo do 400 não segue o contrato ({ erro: string }).
  readonly mensagemServidor: string | null

  constructor(mensagemServidor: string | null) {
    super('meta recusada por entrada inválida')
    this.mensagemServidor = mensagemServidor
  }
}

function eMesOuNulo(valor: unknown): boolean {
  return valor === null || (typeof valor === 'string' && /^\d{4}-\d{2}$/.test(valor))
}

function isMeta(valor: unknown): valor is Meta {
  return (
    typeof valor === 'object' &&
    valor !== null &&
    'id' in valor &&
    typeof valor.id === 'string' &&
    'titulo' in valor &&
    typeof valor.titulo === 'string' &&
    'valorAlvoCentavos' in valor &&
    Number.isSafeInteger(valor.valorAlvoCentavos) &&
    'dataAlvo' in valor &&
    (valor.dataAlvo === null || eDataIso(valor.dataAlvo)) &&
    'aporteMensalCentavos' in valor &&
    inteiroSeguroOuNulo(valor.aporteMensalCentavos) &&
    'acumuladoCentavos' in valor &&
    Number.isSafeInteger(valor.acumuladoCentavos) &&
    'faltaCentavos' in valor &&
    Number.isSafeInteger(valor.faltaCentavos) &&
    'atingida' in valor &&
    typeof valor.atingida === 'boolean' &&
    'prazoVencido' in valor &&
    typeof valor.prazoVencido === 'boolean' &&
    'necessarioPorMesCentavos' in valor &&
    inteiroSeguroOuNulo(valor.necessarioPorMesCentavos) &&
    'chegadaPeloPlano' in valor &&
    eMesOuNulo(valor.chegadaPeloPlano) &&
    'ritmoMensalCentavos' in valor &&
    Number.isSafeInteger(valor.ritmoMensalCentavos) &&
    'chegadaPeloRitmo' in valor &&
    eMesOuNulo(valor.chegadaPeloRitmo)
  )
}

function isAporteMeta(valor: unknown): valor is AporteMeta {
  return (
    typeof valor === 'object' &&
    valor !== null &&
    'id' in valor &&
    typeof valor.id === 'string' &&
    'valorCentavos' in valor &&
    Number.isSafeInteger(valor.valorCentavos) &&
    'data' in valor &&
    eDataIso(valor.data)
  )
}

async function recusarRespostaDeMeta(resposta: Response, origem: string): Promise<never> {
  if (resposta.status === 401) throw new SessaoExpiradaError('sessão expirada')
  if (resposta.status === 503) throw new ServicoIndisponivelError('serviço indisponível')
  if (resposta.status === 404) throw new MetaNaoEncontradaError('meta não encontrada')
  if (resposta.status === 400) throw new MetaInvalidaError(await lerMensagemDeErro(resposta))
  throw new ApiError(`${origem} respondeu ${resposta.status}`)
}

async function enviarMeta(caminho: string, metodo: 'POST' | 'PUT', dados: unknown): Promise<Response> {
  return fetch(`${baseUrl}${caminho}`, {
    method: metodo,
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(dados),
  })
}

export async function fetchMetas(): Promise<Meta[]> {
  const resposta = await fetch(`${baseUrl}/metas`, { credentials: 'same-origin' })
  if (!resposta.ok) return recusarRespostaDeMeta(resposta, '/metas')
  const corpo: unknown = await resposta.json()
  if (!Array.isArray(corpo) || !corpo.every(isMeta)) throw new ApiError('/metas com formato inesperado')
  return corpo
}

export async function postMeta(dados: MetaRequest): Promise<Meta> {
  const resposta = await enviarMeta('/metas', 'POST', dados)
  if (resposta.status !== 201) return recusarRespostaDeMeta(resposta, 'POST /metas')
  const corpo: unknown = await resposta.json()
  if (!isMeta(corpo)) throw new ApiError('POST /metas com formato inesperado')
  return corpo
}

export async function putMeta(id: string, dados: MetaRequest): Promise<Meta> {
  const resposta = await enviarMeta(`/metas/${encodeURIComponent(id)}`, 'PUT', dados)
  if (!resposta.ok) return recusarRespostaDeMeta(resposta, 'PUT /metas/{id}')
  const corpo: unknown = await resposta.json()
  if (!isMeta(corpo)) throw new ApiError('PUT /metas/{id} com formato inesperado')
  return corpo
}

export async function deleteMeta(id: string): Promise<void> {
  const resposta = await fetch(`${baseUrl}/metas/${encodeURIComponent(id)}`, {
    method: 'DELETE',
    credentials: 'same-origin',
  })
  if (resposta.status !== 204) return recusarRespostaDeMeta(resposta, 'DELETE /metas/{id}')
}

export async function fetchAportes(metaId: string): Promise<AporteMeta[]> {
  const resposta = await fetch(`${baseUrl}/metas/${encodeURIComponent(metaId)}/aportes`, {
    credentials: 'same-origin',
  })
  if (!resposta.ok) return recusarRespostaDeMeta(resposta, '/metas/{id}/aportes')
  const corpo: unknown = await resposta.json()
  if (!Array.isArray(corpo) || !corpo.every(isAporteMeta)) {
    throw new ApiError('/metas/{id}/aportes com formato inesperado')
  }
  return corpo
}

// valorCentavos positivo guarda, negativo retira.
export async function postAporte(metaId: string, valorCentavos: number, data: string): Promise<Meta> {
  const resposta = await enviarMeta(`/metas/${encodeURIComponent(metaId)}/aportes`, 'POST', { valorCentavos, data })
  if (resposta.status !== 201) return recusarRespostaDeMeta(resposta, 'POST /metas/{id}/aportes')
  const corpo: unknown = await resposta.json()
  if (!isMeta(corpo)) throw new ApiError('POST /metas/{id}/aportes com formato inesperado')
  return corpo
}

export async function deleteAporte(metaId: string, aporteId: string): Promise<void> {
  const resposta = await fetch(
    `${baseUrl}/metas/${encodeURIComponent(metaId)}/aportes/${encodeURIComponent(aporteId)}`,
    { method: 'DELETE', credentials: 'same-origin' },
  )
  if (resposta.status !== 204) return recusarRespostaDeMeta(resposta, 'DELETE /metas/{id}/aportes/{aporteId}')
}

export type RelatorioDoMes = components['schemas']['RelatorioDoMes']
export type DespesaDaCategoria = components['schemas']['DespesaDaCategoria']
export type ReceitaEDespesaDoMes = components['schemas']['ReceitaEDespesaDoMes']

export class RelatorioInvalidoError extends ApiError {
  // null quando o corpo do 400 não segue o contrato ({ erro: string }).
  readonly mensagemServidor: string | null

  constructor(mensagemServidor: string | null) {
    super('relatório recusado')
    this.mensagemServidor = mensagemServidor
  }
}

function centavosNaoNegativos(valor: unknown): boolean {
  return Number.isSafeInteger(valor) && (valor as number) >= 0
}

function isDespesaDaCategoria(valor: unknown): valor is DespesaDaCategoria {
  return (
    typeof valor === 'object' &&
    valor !== null &&
    'categoriaId' in valor &&
    typeof valor.categoriaId === 'string' &&
    'categoriaNome' in valor &&
    typeof valor.categoriaNome === 'string' &&
    'gastoCentavos' in valor &&
    centavosNaoNegativos(valor.gastoCentavos)
  )
}

function isReceitaEDespesaDoMes(valor: unknown): valor is ReceitaEDespesaDoMes {
  return (
    typeof valor === 'object' &&
    valor !== null &&
    'mes' in valor &&
    eMesIso(valor.mes) &&
    'receitaCentavos' in valor &&
    centavosNaoNegativos(valor.receitaCentavos) &&
    'despesaCentavos' in valor &&
    centavosNaoNegativos(valor.despesaCentavos)
  )
}

function isRelatorioDoMes(valor: unknown): valor is RelatorioDoMes {
  return (
    typeof valor === 'object' &&
    valor !== null &&
    'mes' in valor &&
    eMesIso(valor.mes) &&
    'totalDespesasCentavos' in valor &&
    centavosNaoNegativos(valor.totalDespesasCentavos) &&
    'despesasPorCategoria' in valor &&
    Array.isArray(valor.despesasPorCategoria) &&
    valor.despesasPorCategoria.every(isDespesaDaCategoria) &&
    'receitasEDespesas' in valor &&
    Array.isArray(valor.receitasEDespesas) &&
    valor.receitasEDespesas.every(isReceitaEDespesaDoMes)
  )
}

// mes em AAAA-MM.
export async function fetchRelatorio(mes: string): Promise<RelatorioDoMes> {
  const parametros = new URLSearchParams({ mes })
  const resposta = await fetch(`${baseUrl}/relatorios?${parametros.toString()}`, { credentials: 'same-origin' })
  if (!resposta.ok) {
    if (resposta.status === 401) throw new SessaoExpiradaError('sessão expirada')
    if (resposta.status === 503) throw new ServicoIndisponivelError('serviço indisponível')
    if (resposta.status === 400) throw new RelatorioInvalidoError(await lerMensagemDeErro(resposta))
    throw new ApiError(`/relatorios respondeu ${resposta.status}`)
  }
  const corpo: unknown = await resposta.json()
  if (!isRelatorioDoMes(corpo)) throw new ApiError('/relatorios com formato inesperado')
  return corpo
}

export type PatrimonioMensal = components['schemas']['PatrimonioMensal']
export type PatrimonioDoMes = components['schemas']['PatrimonioDoMes']

function centavosComSinal(valor: unknown): boolean {
  return Number.isSafeInteger(valor)
}

function isPatrimonioDoMes(valor: unknown): valor is PatrimonioDoMes {
  return (
    typeof valor === 'object' &&
    valor !== null &&
    'mes' in valor &&
    eMesIso(valor.mes) &&
    'data' in valor &&
    typeof valor.data === 'string' &&
    /^\d{4}-\d{2}-\d{2}$/.test(valor.data) &&
    'contasCentavos' in valor &&
    centavosComSinal(valor.contasCentavos) &&
    'criptoCentavos' in valor &&
    centavosComSinal(valor.criptoCentavos) &&
    'rendaFixaCentavos' in valor &&
    centavosComSinal(valor.rendaFixaCentavos) &&
    'totalCentavos' in valor &&
    centavosComSinal(valor.totalCentavos) &&
    'posicoesSemCotacao' in valor &&
    centavosNaoNegativos(valor.posicoesSemCotacao)
  )
}

function isPatrimonioMensal(valor: unknown): valor is PatrimonioMensal {
  return (
    typeof valor === 'object' &&
    valor !== null &&
    'mes' in valor &&
    eMesIso(valor.mes) &&
    'meses' in valor &&
    Array.isArray(valor.meses) &&
    valor.meses.every(isPatrimonioDoMes)
  )
}

// mes em AAAA-MM. O 400 reaproveita o erro do relatório: as duas leituras são da mesma tela.
export async function fetchPatrimonioMensal(mes: string): Promise<PatrimonioMensal> {
  const parametros = new URLSearchParams({ mes })
  const resposta = await fetch(`${baseUrl}/patrimonio/mensal?${parametros.toString()}`, { credentials: 'same-origin' })
  if (!resposta.ok) {
    if (resposta.status === 401) throw new SessaoExpiradaError('sessão expirada')
    if (resposta.status === 503) throw new ServicoIndisponivelError('serviço indisponível')
    if (resposta.status === 400) throw new RelatorioInvalidoError(await lerMensagemDeErro(resposta))
    throw new ApiError(`/patrimonio/mensal respondeu ${resposta.status}`)
  }
  const corpo: unknown = await resposta.json()
  if (!isPatrimonioMensal(corpo)) throw new ApiError('/patrimonio/mensal com formato inesperado')
  return corpo
}

export type PossoGastar = components['schemas']['PossoGastar']
export type BeneficioLivre = components['schemas']['BeneficioLivre']

function isBeneficioLivre(valor: unknown): valor is BeneficioLivre {
  return (
    typeof valor === 'object' &&
    valor !== null &&
    'contaId' in valor &&
    typeof valor.contaId === 'string' &&
    'nome' in valor &&
    typeof valor.nome === 'string' &&
    'livreCentavos' in valor &&
    Number.isSafeInteger(valor.livreCentavos)
  )
}

function isProximoSalario(valor: unknown): boolean {
  return (
    valor === null ||
    (typeof valor === 'object' &&
      'data' in valor &&
      eDataIso(valor.data) &&
      'valorCentavos' in valor &&
      Number.isSafeInteger(valor.valorCentavos))
  )
}

function isPossoGastar(valor: unknown): valor is PossoGastar {
  return (
    typeof valor === 'object' &&
    valor !== null &&
    'hoje' in valor &&
    eDataIso(valor.hoje) &&
    'ate' in valor &&
    eDataIso(valor.ate) &&
    'proximoSalario' in valor &&
    isProximoSalario(valor.proximoSalario) &&
    'livreCentavos' in valor &&
    Number.isSafeInteger(valor.livreCentavos) &&
    'menorSaldoEm' in valor &&
    eDataIso(valor.menorSaldoEm) &&
    'dias' in valor &&
    Number.isSafeInteger(valor.dias) &&
    'porDiaCentavos' in valor &&
    (valor.porDiaCentavos === null || Number.isSafeInteger(valor.porDiaCentavos)) &&
    'vencidasCentavos' in valor &&
    Number.isSafeInteger(valor.vencidasCentavos) &&
    'beneficios' in valor &&
    Array.isArray(valor.beneficios) &&
    valor.beneficios.every(isBeneficioLivre)
  )
}

export async function fetchPossoGastar(): Promise<PossoGastar> {
  const resposta = await fetch(`${baseUrl}/posso-gastar`, { credentials: 'same-origin' })
  if (!resposta.ok) return recusarRespostaDeProjecao(resposta, '/posso-gastar')
  const corpo: unknown = await resposta.json()
  if (!isPossoGastar(corpo)) throw new ApiError('/posso-gastar com formato inesperado')
  return corpo
}

export type MfaStatus = components['schemas']['MfaStatus']
export type MfaConfiguracao = components['schemas']['MfaConfiguracao']

async function chamarMfa(caminho: string, init?: RequestInit): Promise<Response> {
  const resposta = await fetch(`${baseUrl}${caminho}`, {
    credentials: 'same-origin',
    ...init,
    headers: init?.body === undefined ? undefined : { 'Content-Type': 'application/json' },
  })
  if (resposta.status === 401) throw new SessaoExpiradaError('sessão expirada')
  if (resposta.status === 503) throw new ServicoIndisponivelError('serviço indisponível')
  if (resposta.status === 429) throw new LimiteTentativasError(lerRetryAfter(resposta))
  if (resposta.status === 400 || resposta.status === 409) {
    throw new MfaRecusadoError(await lerMensagemDeErro(resposta))
  }
  if (!resposta.ok) throw new ApiError(`${caminho} respondeu ${resposta.status}`)
  return resposta
}

function isMfaStatus(valor: unknown): valor is MfaStatus {
  return (
    typeof valor === 'object' &&
    valor !== null &&
    'ativo' in valor &&
    typeof valor.ativo === 'boolean' &&
    'codigosRestantes' in valor &&
    Number.isInteger(valor.codigosRestantes) &&
    Number(valor.codigosRestantes) >= 0
  )
}

export async function fetchMfa(): Promise<MfaStatus> {
  const corpo: unknown = await (await chamarMfa('/auth/mfa')).json()
  if (!isMfaStatus(corpo)) throw new ApiError('/auth/mfa com formato inesperado')
  return corpo
}

export async function postMfaConfigurar(senha: string): Promise<MfaConfiguracao> {
  const resposta = await chamarMfa('/auth/mfa/configurar', { method: 'POST', body: JSON.stringify({ senha }) })
  const corpo: unknown = await resposta.json()
  if (
    typeof corpo !== 'object' ||
    corpo === null ||
    !('segredo' in corpo) ||
    typeof corpo.segredo !== 'string' ||
    !/^[A-Z2-7]+$/.test(corpo.segredo) ||
    !('uri' in corpo) ||
    typeof corpo.uri !== 'string' ||
    !corpo.uri.startsWith('otpauth://totp/')
  ) {
    throw new ApiError('/auth/mfa/configurar com formato inesperado')
  }
  return { segredo: corpo.segredo, uri: corpo.uri }
}

export async function postMfaAtivar(codigo: string): Promise<string[]> {
  const resposta = await chamarMfa('/auth/mfa/ativar', { method: 'POST', body: JSON.stringify({ codigo }) })
  const corpo: unknown = await resposta.json()
  if (
    typeof corpo !== 'object' ||
    corpo === null ||
    !('codigosDeRecuperacao' in corpo) ||
    !Array.isArray(corpo.codigosDeRecuperacao) ||
    !corpo.codigosDeRecuperacao.every((codigo: unknown) => typeof codigo === 'string')
  ) {
    throw new ApiError('/auth/mfa/ativar com formato inesperado')
  }
  return corpo.codigosDeRecuperacao.map(String)
}

export async function postMfaDesativar(senha: string, codigo: string): Promise<void> {
  await chamarMfa('/auth/mfa/desativar', { method: 'POST', body: JSON.stringify({ senha, codigo }) })
}
