import { getWallets } from '@wallet-standard/app'
import { assinarMensagem, conectarCarteira, descobrirCarteiras, ordenarCarteiras } from './conexaoDeCarteira'
import type { CarteiraDescoberta } from './conexaoDeCarteira'
import fonteDoModulo from './conexaoDeCarteira.ts?raw'
import fonteDosErros from './errosDeCarteira.ts?raw'
import fonteDaTela from './Carteiras.tsx?raw'
import { EnderecoDiferenteError, ehRecusaDoUsuario } from './errosDeCarteira'

const ICONE = 'data:image/svg+xml;base64,PHN2Zy8+' as const
const ENDERECO_EVM = '0x52908400098527886e0f7030069857d2e4169ee7'
const ENDERECO_SOLANA = '11111111111111111111111111111112'
const ASSINATURA_EVM = `0x${'ab'.repeat(65)}`

type Chamada = { method: string; params?: unknown }

// Extensão falsa no padrão EIP-6963: anuncia quando o app pede e responde às chamadas do EIP-1193.
function instalarExtensao(rdns: string, nome: string, contas: string[]): { chamadas: Chamada[]; remover: () => void } {
  const chamadas: Chamada[] = []
  const provider = {
    request: vi.fn((chamada: Chamada) => {
      chamadas.push(chamada)
      if (chamada.method === 'eth_requestAccounts' || chamada.method === 'eth_accounts') return Promise.resolve(contas)
      if (chamada.method === 'eth_chainId') return Promise.resolve('0x1')
      if (chamada.method === 'personal_sign') return Promise.resolve(ASSINATURA_EVM)
      return Promise.reject(new Error(`método inesperado: ${chamada.method}`))
    }),
    on: vi.fn(),
    removeListener: vi.fn(),
  }
  const anunciar = (): void => {
    window.dispatchEvent(
      new CustomEvent('eip6963:announceProvider', {
        detail: Object.freeze({ info: { uuid: `uuid-${rdns}`, name: nome, icon: ICONE, rdns }, provider }),
      }),
    )
  }
  window.addEventListener('eip6963:requestProvider', anunciar)
  anunciar()
  return { chamadas, remover: () => window.removeEventListener('eip6963:requestProvider', anunciar) }
}

function instalarWalletSolana(nome: string, endereco: string): { assinatura: Uint8Array; mensagens: Uint8Array[]; remover: () => void } {
  const assinatura = Uint8Array.from({ length: 64 }, (_, i) => i)
  const mensagens: Uint8Array[] = []
  const conta = { address: endereco, publicKey: new Uint8Array(32), chains: ['solana:mainnet'], features: [] }
  const wallet = {
    version: '1.0.0' as const,
    name: nome,
    icon: ICONE,
    chains: ['solana:mainnet'] as const,
    accounts: [],
    features: {
      'standard:connect': { version: '1.0.0', connect: vi.fn(() => Promise.resolve({ accounts: [conta] })) },
      'solana:signMessage': {
        version: '1.0.0',
        signMessage: vi.fn((entrada: { message: Uint8Array }) => {
          mensagens.push(entrada.message)
          return Promise.resolve([{ signedMessage: entrada.message, signature: assinatura }])
        }),
      },
    },
  }
  return { assinatura, mensagens, remover: getWallets().register(wallet) }
}

const limpezas: Array<() => void> = []
afterEach(() => {
  for (const limpar of limpezas.splice(0)) limpar()
})

describe('ordenarCarteiras', () => {
  const carteira = (nome: string, familia: CarteiraDescoberta['familia'] = 'evm'): CarteiraDescoberta => ({
    chave: `${familia}:${nome}`,
    nome,
    familia,
  })

  it('põe Rabby primeiro, Phantom depois e as demais por nome', () => {
    const ordenada = ordenarCarteiras([carteira('Zerion'), carteira('Phantom'), carteira('Coinbase Wallet'), carteira('Rabby Wallet')])
    expect(ordenada.map((item) => item.nome)).toEqual(['Rabby Wallet', 'Phantom', 'Coinbase Wallet', 'Zerion'])
  })

  it('identifica pelo nome sem diferenciar maiúscula e mantém as duas famílias da mesma carteira', () => {
    const ordenada = ordenarCarteiras([carteira('Backpack', 'solana'), carteira('PHANTOM', 'solana'), carteira('rabby'), carteira('PHANTOM')])
    expect(ordenada.map((item) => item.chave)).toEqual(['evm:rabby', 'evm:PHANTOM', 'solana:PHANTOM', 'solana:Backpack'])
  })

  it('não altera a lista recebida', () => {
    const original = [carteira('Zerion'), carteira('Rabby')]
    ordenarCarteiras(original)
    expect(original.map((item) => item.nome)).toEqual(['Zerion', 'Rabby'])
  })
})

describe('ehRecusaDoUsuario', () => {
  it('reconhece o código 4001, o erro do viem, a mensagem do Wallet Standard e a causa aninhada', () => {
    expect(ehRecusaDoUsuario({ code: 4001 })).toBe(true)
    expect(ehRecusaDoUsuario({ name: 'UserRejectedRequestError' })).toBe(true)
    expect(ehRecusaDoUsuario(new Error('User rejected the request.'))).toBe(true)
    expect(ehRecusaDoUsuario(new Error('falhou', { cause: { code: 4001 } }))).toBe(true)
  })

  it('não confunde outro erro com recusa', () => {
    expect(ehRecusaDoUsuario(new Error('timeout'))).toBe(false)
    expect(ehRecusaDoUsuario({ code: -32603 })).toBe(false)
    expect(ehRecusaDoUsuario('4001')).toBe(false)
  })
})

describe('descoberta e conexão', () => {
  it('lista a extensão EIP-6963 anunciada e a carteira Solana do Wallet Standard, Rabby primeiro', async () => {
    const rabby = instalarExtensao('io.rabby', 'Rabby Wallet', [ENDERECO_EVM])
    const outra = instalarExtensao('app.zerion', 'Zerion', [ENDERECO_EVM])
    const solana = instalarWalletSolana('Phantom', ENDERECO_SOLANA)
    limpezas.push(rabby.remover, outra.remover, solana.remover)

    const lista = await descobrirCarteiras()
    const nomes = lista.map((item) => `${item.nome}/${item.familia}`)
    expect(nomes.indexOf('Rabby Wallet/evm')).toBe(0)
    expect(nomes).toContain('Zerion/evm')
    expect(nomes).toContain('Phantom/solana')
    expect(nomes.indexOf('Phantom/solana')).toBeLessThan(nomes.indexOf('Zerion/evm'))
  })

  it('ignora wallet do padrão que não assina mensagem Solana', async () => {
    const remover = getWallets().register({
      version: '1.0.0',
      name: 'SoConecta',
      icon: ICONE,
      chains: ['solana:mainnet'],
      accounts: [],
      features: { 'standard:connect': { version: '1.0.0', connect: vi.fn() } },
    })
    limpezas.push(remover)
    const lista = await descobrirCarteiras()
    expect(lista.map((item) => item.nome)).not.toContain('SoConecta')
  })

  it('conecta no EVM pedindo as contas e devolve o endereço sem pedir transação', async () => {
    const extensao = instalarExtensao('io.rabby.conecta', 'Rabby Conecta', [ENDERECO_EVM])
    limpezas.push(extensao.remover)
    const carteira = (await descobrirCarteiras()).find((item) => item.nome === 'Rabby Conecta')
    expect(carteira).toBeDefined()
    if (carteira === undefined) return

    // O wagmi devolve o endereço com checksum; o que importa é ser o mesmo.
    expect((await conectarCarteira(carteira)).toLowerCase()).toBe(ENDERECO_EVM)
    // Segunda conexão seguida: não pode travar em "já conectado".
    expect((await conectarCarteira(carteira)).toLowerCase()).toBe(ENDERECO_EVM)
    expect(extensao.chamadas.some((chamada) => chamada.method === 'eth_requestAccounts')).toBe(true)
    expect(extensao.chamadas.map((chamada) => chamada.method)).not.toContain('eth_sendTransaction')
  })

  it('assina no EVM o texto exato com personal_sign e devolve o hex', async () => {
    const extensao = instalarExtensao('io.rabby.assina', 'Rabby Assina', [ENDERECO_EVM])
    limpezas.push(extensao.remover)
    const carteira = (await descobrirCarteiras()).find((item) => item.nome === 'Rabby Assina')
    if (carteira === undefined) throw new Error('carteira não descoberta')

    const mensagem = 'ballast.app quer que você prove\nNonce: 123'
    const assinatura = await assinarMensagem(carteira, ENDERECO_EVM.toUpperCase().replace('0X', '0x'), mensagem)
    expect(assinatura).toBe(ASSINATURA_EVM)

    const pedido = extensao.chamadas.find((chamada) => chamada.method === 'personal_sign')
    expect(pedido).toBeDefined()
    const parametros = pedido?.params
    if (!Array.isArray(parametros)) throw new Error('personal_sign sem parâmetros')
    const textoAssinado = new TextDecoder().decode(
      Uint8Array.from(String(parametros[0]).slice(2).match(/../g) ?? [], (par) => parseInt(par, 16)),
    )
    expect(textoAssinado).toBe(mensagem)
    expect(String(parametros[1]).toLowerCase()).toBe(ENDERECO_EVM)
  })

  it('recusa assinar quando a conta ativa da carteira não é a do endereço', async () => {
    const extensao = instalarExtensao('io.rabby.outra', 'Rabby Outra Conta', ['0x0000000000000000000000000000000000000001'])
    limpezas.push(extensao.remover)
    const carteira = (await descobrirCarteiras()).find((item) => item.nome === 'Rabby Outra Conta')
    if (carteira === undefined) throw new Error('carteira não descoberta')

    await expect(assinarMensagem(carteira, ENDERECO_EVM, 'texto')).rejects.toBeInstanceOf(EnderecoDiferenteError)
    expect(extensao.chamadas.map((chamada) => chamada.method)).not.toContain('personal_sign')
  })

  it('conecta e assina no Solana com os bytes UTF-8 e devolve base64', async () => {
    const solana = instalarWalletSolana('Phantom Teste', ENDERECO_SOLANA)
    limpezas.push(solana.remover)
    const carteira = (await descobrirCarteiras()).find((item) => item.nome === 'Phantom Teste')
    if (carteira === undefined) throw new Error('carteira não descoberta')

    expect(await conectarCarteira(carteira)).toBe(ENDERECO_SOLANA)
    const assinatura = await assinarMensagem(carteira, ENDERECO_SOLANA, 'olá\nNonce: 1')
    expect(assinatura).toBe(btoa(String.fromCharCode(...solana.assinatura)))
    expect(new TextDecoder().decode(solana.mensagens[0])).toBe('olá\nNonce: 1')
    await expect(assinarMensagem(carteira, 'OutroEndereco', 'x')).rejects.toBeInstanceOf(EnderecoDiferenteError)
  })
})

describe('varredura do código', () => {
  const fontes = { 'conexaoDeCarteira.ts': fonteDoModulo, 'errosDeCarteira.ts': fonteDosErros, 'Carteiras.tsx': fonteDaTela }

  it.each(Object.entries(fontes))('%s não chama transação nem assinatura de transação', (_nome, fonte) => {
    expect(fonte).not.toMatch(/sendTransaction|signTransaction|signAndSendTransaction|sendRawTransaction/i)
  })

  it.each(Object.entries(fontes))('%s não usa console nem storage do navegador', (_nome, fonte) => {
    expect(fonte).not.toMatch(/console\.|localStorage|sessionStorage|indexedDB/)
  })
})
