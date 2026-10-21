import { randomBytes } from 'node:crypto'
import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'

const MODOS = ['claro', 'escuro'] as const

// Cada passo vira duas capturas, uma por modo. O fluxo roda sempre no claro e o escuro é só
// emulado na hora da foto, para os dois modos mostrarem o mesmo estado.
async function capturar(page: Page, projeto: string, passo: string): Promise<void> {
  for (const modo of MODOS) {
    await page.emulateMedia({ colorScheme: modo === 'claro' ? 'light' : 'dark' })
    await page.screenshot({
      path: `e2e/capturas/${projeto}-${passo}-${modo}.png`,
      fullPage: true,
    })
  }
  await page.emulateMedia({ colorScheme: 'light' })
}

// O espaço antes do valor é não separável no Intl, então \s cobre.
function reais(texto: string): RegExp {
  return new RegExp(`R\\$\\s${texto.replace(/[.]/g, '\\.')}`)
}

test('fluxo principal de ponta a ponta', async ({ page }, info) => {
  const projeto = info.project.name
  const sufixo = randomBytes(6).toString('hex')
  // Fica fora do .env e do repositório: o usuário só existe neste banco descartável.
  const email = `e2e-${sufixo}@example.com`
  const senha = `Aa1-${randomBytes(12).toString('hex')}`

  await test.step('cadastro', async () => {
    await page.goto('/')
    await expect(page.getByRole('form', { name: 'Entrar' })).toBeVisible()
    await capturar(page, projeto, '01-login')

    await page.getByRole('button', { name: 'Criar conta' }).click()
    const cadastro = page.getByRole('form', { name: 'Criar conta' })
    await cadastro.getByLabel('Nome (opcional)').fill('Teste E2E')
    await cadastro.getByLabel('E-mail').fill(email)
    await cadastro.getByLabel('Senha', { exact: true }).fill(senha)
    await cadastro.getByLabel('Confirmar senha').fill(senha)
    await capturar(page, projeto, '02-cadastro')
    await cadastro.getByRole('button', { name: 'Criar conta' }).click()
    await expect(page.getByText('Você está logado como')).toBeVisible()
  })

  const novaConta = page.getByRole('form', { name: 'Nova conta' })
  const secaoDeContas = page.getByRole('region', { name: 'Contas', exact: true })
  const conta = (nome: string) => secaoDeContas.getByRole('listitem').filter({ hasText: nome })

  await test.step('conta corrente com saldo inicial', async () => {
    await novaConta.getByLabel('Nome').fill('Corrente E2E')
    await novaConta.getByLabel('Saldo inicial em reais (opcional)').fill('1.000,00')
    await novaConta.getByRole('button', { name: 'Criar conta' }).click()
    await expect(conta('Corrente E2E')).toContainText(reais('1.000,00'))
    await capturar(page, projeto, '03-conta-corrente')
  })

  await test.step('poupança para receber a transferência', async () => {
    await novaConta.getByLabel('Nome').fill('Poupança E2E')
    await novaConta.getByLabel('Tipo').selectOption({ label: 'Poupança' })
    await novaConta.getByRole('button', { name: 'Criar conta' }).click()
    await expect(conta('Poupança E2E')).toBeVisible()
  })

  await test.step('cartão de crédito', async () => {
    await novaConta.getByLabel('Nome').fill('Cartão E2E')
    await novaConta.getByLabel('Tipo').selectOption({ label: 'Cartão de crédito' })
    await novaConta.getByLabel('Limite em reais').fill('5.000,00')
    await novaConta.getByLabel('Dia de fechamento').fill('10')
    await novaConta.getByLabel('Dia de vencimento').fill('20')
    await novaConta.getByLabel('Conta de pagamento').selectOption({ label: 'Corrente E2E' })
    await capturar(page, projeto, '04-novo-cartao')
    await novaConta.getByRole('button', { name: 'Criar conta' }).click()
    await expect(conta('Cartão E2E')).toContainText('Disponível')
  })

  const lancamento = page.getByRole('form', { name: 'Novo lançamento' })

  await test.step('despesa na conta corrente', async () => {
    await lancamento.getByLabel('Título').fill('Mercado')
    await lancamento.getByLabel('Valor em reais').fill('100,00')
    await lancamento.getByLabel('Conta').selectOption({ label: 'Corrente E2E' })
    await lancamento.getByRole('button', { name: 'Adicionar lançamento' }).click()
    await expect(conta('Corrente E2E')).toContainText(reais('900,00'))
    await expect(page.getByRole('region', { name: 'Lançamentos' })).toContainText('Mercado')
    await capturar(page, projeto, '05-despesa')
  })

  await test.step('compra parcelada no cartão', async () => {
    await lancamento.getByLabel('Título').fill('Notebook')
    await lancamento.getByLabel('Valor em reais').fill('600,00')
    await lancamento.getByLabel('Conta').selectOption({ label: 'Cartão E2E' })
    await lancamento.getByLabel('Parcelar').selectOption({ label: '3x' })
    await capturar(page, projeto, '06-compra-no-cartao')
    await lancamento.getByRole('button', { name: 'Adicionar lançamento' }).click()
    // O formulário se esvazia quando o servidor aceita; o erro aparece como alert.
    await expect(lancamento.getByLabel('Título')).toHaveValue('')
    await expect(lancamento.getByRole('alert')).toHaveCount(0)
  })

  await test.step('recorrência mensal', async () => {
    const recorrencia = page.getByRole('form', { name: 'Nova recorrência' })
    await recorrencia.getByLabel('Título').fill('Aluguel')
    await recorrencia.getByLabel('Valor em reais').fill('300,00')
    await recorrencia.getByLabel('Conta').selectOption({ label: 'Corrente E2E' })
    await recorrencia.getByLabel('Frequência').selectOption({ label: 'Mensal' })
    await recorrencia.getByRole('button', { name: 'Adicionar recorrência' }).click()
    await expect(page.getByRole('region', { name: 'Recorrências' })).toContainText('Aluguel')
    await capturar(page, projeto, '07-recorrencia')
  })

  await test.step('transferência entre contas', async () => {
    await page.getByRole('button', { name: 'Transferência', exact: true }).click()
    const transferencia = page.getByRole('form', { name: 'Nova transferência' })
    await transferencia.getByLabel('Conta de origem').selectOption({ label: 'Corrente E2E' })
    await transferencia.getByLabel('Conta de destino').selectOption({ label: 'Poupança E2E' })
    await transferencia.getByLabel('Valor em reais').fill('200,00')
    await transferencia.getByLabel('Título (opcional)').fill('Reserva')
    await capturar(page, projeto, '08-transferencia')
    await transferencia.getByRole('button', { name: 'Adicionar transferência' }).click()
    // 1.000 inicial, menos 100 de despesa e 200 transferidos.
    await expect(conta('Corrente E2E')).toContainText(reais('700,00'))
    await expect(conta('Poupança E2E')).toContainText(reais('200,00'))
  })

  await test.step('saldo projetado', async () => {
    const projetado = page.getByRole('region', { name: 'Saldo projetado' })
    await expect(projetado).toBeVisible()
    await expect(projetado.getByRole('alert')).toHaveCount(0)
    await projetado.scrollIntoViewIfNeeded()
    await capturar(page, projeto, '09-saldo-projetado')
  })

  await test.step('faturas do cartão', async () => {
    await page.getByRole('button', { name: 'Faturas de Cartão E2E' }).click()
    await expect(page.getByRole('heading', { name: 'Faturas de Cartão E2E' })).toBeVisible()
    await expect(page.getByText('Carregando faturas...')).toHaveCount(0)
    await capturar(page, projeto, '10-faturas')
  })

  await test.step('sair', async () => {
    await page.getByRole('button', { name: 'Sair' }).click()
    await expect(page.getByRole('form', { name: 'Entrar' })).toBeVisible()
    await capturar(page, projeto, '11-saiu')
  })
})
