// Carregado por import() só no clique em "Conectar carteira" ou "Provar posse" (ADR-072). O app só
// lê o endereço e assina texto: transação não existe aqui, e um teste varre este arquivo para garantir.
import { connect, createConfig, disconnect, http, signMessage } from '@wagmi/core'
import { base, mainnet } from '@wagmi/core/chains'
import { getWallets } from '@wallet-standard/app'
import type { Wallets } from '@wallet-standard/app'
import { EnderecoDiferenteError } from './errosDeCarteira'

export type FamiliaDeCarteira = 'evm' | 'solana'

export type CarteiraDescoberta = {
  chave: string
  nome: string
  familia: FamiliaDeCarteira
}

const PRIORIDADE_DOS_NOMES = ['rabby', 'phantom']

function prioridadeDoNome(nome: string): number {
  const normalizado = nome.trim().toLowerCase()
  const posicao = PRIORIDADE_DOS_NOMES.findIndex((prioritario) => normalizado.startsWith(prioritario))
  return posicao === -1 ? PRIORIDADE_DOS_NOMES.length : posicao
}

// O nome anunciado só decide a ordem; ele não prova que a carteira é quem diz ser.
export function ordenarCarteiras<T extends { nome: string; familia: FamiliaDeCarteira }>(lista: readonly T[]): T[] {
  return [...lista].sort(
    (a, b) =>
      prioridadeDoNome(a.nome) - prioridadeDoNome(b.nome) ||
      a.nome.localeCompare(b.nome, 'pt-BR', { sensitivity: 'base' }) ||
      a.familia.localeCompare(b.familia),
  )
}

function criarConfigEvm() {
  return createConfig({
    chains: [mainnet, base],
    // Nenhuma chamada passa por estes transportes: conectar e assinar falam só com a extensão.
    transports: { [mainnet.id]: http(), [base.id]: http() },
    // Sem storage o wagmi não persiste nada no navegador, e o endereço fica só em memória.
    storage: null,
    multiInjectedProviderDiscovery: true,
  })
}

let configEvm: ReturnType<typeof criarConfigEvm> | null = null

function obterConfigEvm(): ReturnType<typeof criarConfigEvm> {
  configEvm ??= criarConfigEvm()
  return configEvm
}

type WalletPadrao = ReturnType<Wallets['get']>[number]
type ContaPadrao = WalletPadrao['accounts'][number]

type RecursoConectar = { connect: () => Promise<{ accounts: readonly ContaPadrao[] }> }
type RecursoAssinar = {
  signMessage: (entrada: { account: ContaPadrao; message: Uint8Array }) => Promise<readonly { signature: Uint8Array }[]>
}

function temRecursoConectar(recurso: unknown): recurso is RecursoConectar {
  return typeof recurso === 'object' && recurso !== null && 'connect' in recurso && typeof recurso.connect === 'function'
}

function temRecursoAssinar(recurso: unknown): recurso is RecursoAssinar {
  return typeof recurso === 'object' && recurso !== null && 'signMessage' in recurso && typeof recurso.signMessage === 'function'
}

function servePraSolana(wallet: WalletPadrao): boolean {
  return temRecursoConectar(wallet.features['standard:connect']) && temRecursoAssinar(wallet.features['solana:signMessage'])
}

export async function descobrirCarteiras(): Promise<CarteiraDescoberta[]> {
  const config = obterConfigEvm()
  // As extensões respondem ao pedido do EIP-6963 no mesmo instante; um ciclo do laço de eventos basta.
  await new Promise<void>((resolver) => setTimeout(resolver, 0))
  const evm: CarteiraDescoberta[] = config.connectors.map((conector) => ({
    chave: `evm:${conector.id}`,
    nome: conector.name,
    familia: 'evm',
  }))
  const solana: CarteiraDescoberta[] = getWallets()
    .get()
    .filter(servePraSolana)
    .map((wallet) => ({ chave: `solana:${wallet.name}`, nome: wallet.name, familia: 'solana' }))
  return ordenarCarteiras([...evm, ...solana])
}

function acharConectorEvm(carteira: CarteiraDescoberta) {
  const conector = obterConfigEvm().connectors.find((candidato) => `evm:${candidato.id}` === carteira.chave)
  if (conector === undefined) throw new Error('carteira EVM não está mais disponível')
  return conector
}

function acharWalletPadrao(carteira: CarteiraDescoberta): { wallet: WalletPadrao; conectar: RecursoConectar } {
  const wallet = getWallets()
    .get()
    .find((candidata) => `solana:${candidata.name}` === carteira.chave)
  const conectar = wallet?.features['standard:connect']
  if (wallet === undefined || !temRecursoConectar(conectar)) throw new Error('carteira Solana não está mais disponível')
  return { wallet, conectar }
}

function mesmoEndereco(familia: FamiliaDeCarteira, a: string, b: string): boolean {
  // Endereço EVM ignora maiúscula (checksum); o base58 do Solana diferencia.
  return familia === 'evm' ? a.toLowerCase() === b.toLowerCase() : a === b
}

function paraBase64(bytes: Uint8Array): string {
  return btoa(String.fromCharCode(...bytes))
}

// Pede as contas à extensão (eth_requestAccounts ou standard:connect) e devolve o endereço.
export async function conectarCarteira(carteira: CarteiraDescoberta): Promise<string> {
  if (carteira.familia === 'evm') {
    const config = obterConfigEvm()
    const conector = acharConectorEvm(carteira)
    const { accounts } = await connect(config, { connector: conector })
    // Desconecta para a próxima tentativa não bater em "já conectado"; o wagmi aqui só vive em memória.
    await disconnect(config, { connector: conector })
    return accounts[0]
  }
  const { conectar } = acharWalletPadrao(carteira)
  const { accounts } = await conectar.connect()
  const conta = accounts[0]
  if (conta === undefined) throw new Error('a carteira não entregou nenhuma conta')
  return conta.address
}

// Assina o texto exato recebido do servidor. Devolve hex 0x no EVM e base64 no Solana.
export async function assinarMensagem(carteira: CarteiraDescoberta, endereco: string, mensagem: string): Promise<string> {
  if (carteira.familia === 'evm') {
    const config = obterConfigEvm()
    const conector = acharConectorEvm(carteira)
    const { accounts } = await connect(config, { connector: conector })
    try {
      const conta = accounts.find((candidata) => mesmoEndereco('evm', candidata, endereco))
      if (conta === undefined) throw new EnderecoDiferenteError('a conta da carteira não é a deste endereço')
      return await signMessage(config, { account: conta, connector: conector, message: mensagem })
    } finally {
      await disconnect(config, { connector: conector })
    }
  }
  const { wallet, conectar } = acharWalletPadrao(carteira)
  const { accounts } = await conectar.connect()
  const conta = accounts.find((candidata) => mesmoEndereco('solana', candidata.address, endereco))
  if (conta === undefined) throw new EnderecoDiferenteError('a conta da carteira não é a deste endereço')
  const assinar = wallet.features['solana:signMessage']
  if (!temRecursoAssinar(assinar)) throw new Error('a carteira não assina mensagem')
  const [saida] = await assinar.signMessage({ account: conta, message: new TextEncoder().encode(mensagem) })
  if (saida === undefined) throw new Error('a carteira não devolveu a assinatura')
  return paraBase64(saida.signature)
}
