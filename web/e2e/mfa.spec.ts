import { createHmac, randomBytes } from 'node:crypto'
import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'

const ALFABETO = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567'
const PASSO_MS = 30_000

function decodificarBase32(texto: string): Buffer {
  let bits = 0
  let acumulado = 0
  const bytes: number[] = []
  for (const letra of texto) {
    acumulado = (acumulado << 5) | ALFABETO.indexOf(letra)
    bits += 5
    if (bits >= 8) {
      bytes.push((acumulado >>> (bits - 8)) & 0xff)
      bits -= 8
    }
  }
  return Buffer.from(bytes)
}

// RFC 6238 com os parâmetros do ADR-050: HMAC-SHA1, passo de 30 s, 6 dígitos.
function totp(segredo: string, passo: number): string {
  const contador = Buffer.alloc(8)
  contador.writeBigUInt64BE(BigInt(passo))
  const hash = createHmac('sha1', decodificarBase32(segredo)).update(contador).digest()
  const deslocamento = hash[hash.length - 1] & 0x0f
  const valor = hash.readUInt32BE(deslocamento) & 0x7fffffff
  return String(valor % 1_000_000).padStart(6, '0')
}

// O servidor não aceita o mesmo passo duas vezes (ADR-050), então cada código tem que ser de um
// passo mais novo que o anterior. Também espera virar o passo se faltar menos de 2 s, para o
// código não vencer no caminho até o servidor.
async function proximoCodigo(page: Page, segredo: string, ultimoPasso: number): Promise<{ codigo: string; passo: number }> {
  for (;;) {
    const agora = Date.now()
    const passo = Math.floor(agora / PASSO_MS)
    const restante = PASSO_MS - (agora % PASSO_MS)
    if (passo > ultimoPasso && restante >= 2000) return { codigo: totp(segredo, passo), passo }
    await page.waitForTimeout(restante + 100)
  }
}

async function entrar(page: Page, email: string, senha: string): Promise<void> {
  const login = page.getByRole('form', { name: 'Entrar' })
  await login.getByLabel('E-mail').fill(email)
  await login.getByLabel('Senha').fill(senha)
  await login.getByRole('button', { name: 'Entrar' }).click()
}

test('MFA: liga, entra com o código do app e entra com um código de recuperação', async ({ page }) => {
  // Cada código de app precisa de um passo novo, então o teste pode esperar até 30 s duas vezes.
  test.setTimeout(150_000)
  const sufixo = randomBytes(6).toString('hex')
  const email = `e2e-mfa-${sufixo}@example.com`
  const senha = `Aa1-${randomBytes(12).toString('hex')}`

  await page.goto('/')
  await page.getByRole('button', { name: 'Criar conta' }).click()
  const cadastro = page.getByRole('form', { name: 'Criar conta' })
  await cadastro.getByLabel('E-mail').fill(email)
  await cadastro.getByLabel('Senha', { exact: true }).fill(senha)
  await cadastro.getByLabel('Confirmar senha').fill(senha)
  await cadastro.getByRole('button', { name: 'Criar conta' }).click()
  await expect(page.getByRole('heading', { level: 2, name: 'Início' })).toBeVisible()

  let segredo = ''
  let ultimoPasso = 0
  let recuperacao = ''

  await test.step('liga o MFA lendo o segredo da tela', async () => {
    await page.getByRole('link', { name: 'Segurança' }).click()
    await expect(page.getByText('Verificação em duas etapas desligada.')).toBeVisible()
    await page.getByRole('button', { name: 'Ligar' }).click()
    const confirmar = page.getByRole('form', { name: 'Confirmar senha' })
    await confirmar.getByLabel('Senha').fill(senha)
    await confirmar.getByRole('button', { name: 'Continuar' }).click()

    const ativar = page.getByRole('form', { name: 'Ativar verificação' })
    const textoDoSegredo = await ativar.locator('.mfa-segredo code').innerText()
    segredo = textoDoSegredo.replace(/\s/g, '')
    expect(segredo).toMatch(/^[A-Z2-7]+$/)

    const emitido = await proximoCodigo(page, segredo, ultimoPasso)
    ultimoPasso = emitido.passo
    await ativar.getByLabel('Código do app').fill(emitido.codigo)
    await ativar.getByRole('button', { name: 'Ativar' }).click()

    const lista = page.getByRole('list', { name: 'Códigos de recuperação' })
    await expect(lista.getByRole('listitem')).toHaveCount(10)
    recuperacao = (await lista.getByRole('listitem').first().innerText()).trim()
    await page.getByRole('button', { name: 'Já guardei' }).click()
    await expect(page.getByText('10 códigos de recuperação restantes.')).toBeVisible()
  })

  await test.step('sai e entra com o código do app', async () => {
    await page.getByRole('button', { name: 'Sair' }).click()
    await entrar(page, email, senha)
    const verificacao = page.getByRole('form', { name: 'Código de verificação' })
    await expect(verificacao).toBeVisible()

    const emitido = await proximoCodigo(page, segredo, ultimoPasso)
    ultimoPasso = emitido.passo
    await verificacao.getByLabel('Código').fill(emitido.codigo)
    await verificacao.getByRole('button', { name: 'Confirmar' }).click()
    // A rota continua em Segurança depois de sair e entrar, então a confirmação é a navegação.
    await expect(page.getByRole('navigation', { name: 'Seções' })).toBeVisible()
  })

  await test.step('sai e entra com um código de recuperação', async () => {
    await page.getByRole('button', { name: 'Sair' }).click()
    await entrar(page, email, senha)
    const verificacao = page.getByRole('form', { name: 'Código de verificação' })
    await verificacao.getByLabel('Código').fill(recuperacao)
    await verificacao.getByRole('button', { name: 'Confirmar' }).click()
    // A rota continua em Segurança depois de sair e entrar, então a confirmação é a navegação.
    await expect(page.getByRole('navigation', { name: 'Seções' })).toBeVisible()

    await page.getByRole('link', { name: 'Segurança' }).click()
    await expect(page.getByText('9 códigos de recuperação restantes.')).toBeVisible()
  })
})
