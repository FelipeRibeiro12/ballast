import { randomBytes } from 'node:crypto'
import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'

const MODOS = ['claro', 'escuro'] as const

// Cada passo vira duas capturas, uma por modo. O fluxo roda sempre no claro e o escuro é só
// emulado na hora da foto, para os dois modos mostrarem o mesmo estado.
async function capturar(page: Page, projeto: string, passo: string): Promise<void> {
  for (const modo of MODOS) {
    await page.emulateMedia({ colorScheme: modo === 'claro' ? 'light' : 'dark' })
    // Espera as transições de cor e a animação do painel terminarem antes da foto.
    await page.waitForTimeout(400)
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

type Secao = 'Início' | 'Lançamentos' | 'Contas' | 'Recorrências'

// A navegação é um só <nav>: barra embaixo no celular, links no topo no desktop.
async function irPara(page: Page, secao: Secao): Promise<void> {
  await page.getByRole('navigation', { name: 'Seções' }).getByRole('link', { name: secao }).click()
  await expect(page.getByRole('heading', { level: 2, name: secao })).toBeVisible()
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
    await expect(page.getByRole('heading', { level: 2, name: 'Início' })).toBeVisible()
    await expect(page.getByText('Teste E2E')).toBeVisible()
  })

  const secaoDeContas = page.getByRole('region', { name: 'Contas', exact: true })
  const conta = (nome: string) => secaoDeContas.getByRole('listitem').filter({ hasText: nome })
  const painel = page.getByRole('dialog')

  // Abre o painel de nova conta, preenche o que a função pedir e deixa o formulário à vista.
  async function abrirNovaConta() {
    await page.getByRole('button', { name: 'Nova conta' }).click()
    const form = page.getByRole('form', { name: 'Nova conta' })
    await expect(form).toBeVisible()
    return form
  }

  await test.step('conta corrente com saldo inicial', async () => {
    await irPara(page, 'Contas')
    const novaConta = await abrirNovaConta()
    await novaConta.getByLabel('Nome').fill('Corrente E2E')
    await novaConta.getByLabel('Saldo inicial em reais (opcional)').fill('1.000,00')
    await novaConta.getByRole('button', { name: 'Criar conta' }).click()
    await expect(painel).toHaveCount(0)
    await expect(conta('Corrente E2E')).toContainText(reais('1.000,00'))
    await capturar(page, projeto, '03-conta-corrente')
  })

  await test.step('poupança para receber a transferência', async () => {
    const novaConta = await abrirNovaConta()
    await novaConta.getByLabel('Nome').fill('Poupança E2E')
    await novaConta.getByLabel('Tipo').selectOption({ label: 'Poupança' })
    await novaConta.getByRole('button', { name: 'Criar conta' }).click()
    await expect(conta('Poupança E2E')).toBeVisible()
  })

  await test.step('cartão de crédito', async () => {
    const novaConta = await abrirNovaConta()
    await novaConta.getByLabel('Nome').fill('Cartão E2E')
    await novaConta.getByLabel('Tipo').selectOption({ label: 'Cartão de crédito' })
    await novaConta.getByLabel('Limite em reais').fill('5.000,00')
    await novaConta.getByLabel('Dia de fechamento').fill('10')
    await novaConta.getByLabel('Dia de vencimento').fill('20')
    await novaConta.getByLabel('Conta de pagamento').selectOption({ label: 'Corrente E2E' })
    await capturar(page, projeto, '04-novo-cartao')
    await novaConta.getByRole('button', { name: 'Criar conta' }).click()
    await expect(painel).toHaveCount(0)
    await expect(conta('Cartão E2E')).toContainText('Disponível')
  })

  const lancamento = page.getByRole('form', { name: 'Novo lançamento' })

  async function abrirNovoLancamento(): Promise<void> {
    await irPara(page, 'Lançamentos')
    await page.getByRole('button', { name: 'Novo', exact: true }).click()
    await expect(lancamento).toBeVisible()
  }

  await test.step('despesa na conta corrente', async () => {
    await abrirNovoLancamento()
    await lancamento.getByLabel('Título').fill('Mercado')
    await lancamento.getByLabel('Valor em reais').fill('100,00')
    await lancamento.getByLabel('Conta').selectOption({ label: 'Corrente E2E' })
    await capturar(page, projeto, '05-novo-lancamento')
    await lancamento.getByRole('button', { name: 'Adicionar lançamento' }).click()
    await expect(painel).toHaveCount(0)
    await expect(page.getByRole('region', { name: 'Lançamentos' })).toContainText('Mercado')
    await capturar(page, projeto, '06-despesa')
    await irPara(page, 'Contas')
    await expect(conta('Corrente E2E')).toContainText(reais('900,00'))
  })

  await test.step('compra parcelada no cartão', async () => {
    await abrirNovoLancamento()
    await lancamento.getByLabel('Título').fill('Notebook')
    await lancamento.getByLabel('Valor em reais').fill('600,00')
    await lancamento.getByLabel('Conta').selectOption({ label: 'Cartão E2E' })
    await lancamento.getByLabel('Parcelar').selectOption({ label: '3x' })
    await capturar(page, projeto, '07-compra-no-cartao')
    await lancamento.getByRole('button', { name: 'Adicionar lançamento' }).click()
    // O painel fecha quando o servidor aceita; se recusar, o erro aparece como alert dentro dele.
    await expect(painel).toHaveCount(0)
  })

  await test.step('recorrência mensal', async () => {
    await irPara(page, 'Recorrências')
    await page.getByRole('button', { name: 'Nova recorrência' }).click()
    const recorrencia = page.getByRole('form', { name: 'Nova recorrência' })
    await recorrencia.getByLabel('Título').fill('Aluguel')
    await recorrencia.getByLabel('Valor em reais').fill('300,00')
    await recorrencia.getByLabel('Conta').selectOption({ label: 'Corrente E2E' })
    await recorrencia.getByLabel('Frequência').selectOption({ label: 'Mensal' })
    await recorrencia.getByRole('button', { name: 'Adicionar recorrência' }).click()
    await expect(painel).toHaveCount(0)
    await expect(page.getByRole('region', { name: 'Recorrências' })).toContainText('Aluguel')
    await capturar(page, projeto, '08-recorrencia')
  })

  await test.step('transferência entre contas', async () => {
    await abrirNovoLancamento()
    await page.getByRole('button', { name: 'Transferência', exact: true }).click()
    const transferencia = page.getByRole('form', { name: 'Nova transferência' })
    await transferencia.getByLabel('Conta de origem').selectOption({ label: 'Corrente E2E' })
    await transferencia.getByLabel('Conta de destino').selectOption({ label: 'Poupança E2E' })
    await transferencia.getByLabel('Valor em reais').fill('200,00')
    await transferencia.getByLabel('Título (opcional)').fill('Reserva')
    await capturar(page, projeto, '09-transferencia')
    await transferencia.getByRole('button', { name: 'Adicionar transferência' }).click()
    await expect(painel).toHaveCount(0)
    await irPara(page, 'Contas')
    // 1.000 inicial, menos 100 de despesa e 200 transferidos.
    await expect(conta('Corrente E2E')).toContainText(reais('700,00'))
    await expect(conta('Poupança E2E')).toContainText(reais('200,00'))
  })

  await test.step('saldo projetado no início', async () => {
    await irPara(page, 'Início')
    const projetado = page.getByRole('region', { name: 'Saldo projetado' })
    await expect(projetado).toBeVisible()
    await expect(projetado.getByRole('img', { name: /saldo projetado/i })).toBeVisible()
    await expect(projetado.getByRole('alert')).toHaveCount(0)
    await expect(page.getByRole('region', { name: 'Resumo das contas' })).toContainText('Saldo nas contas ativas')
    await capturar(page, projeto, '10-saldo-projetado')
  })

  await test.step('faturas do cartão', async () => {
    await irPara(page, 'Contas')
    await page.getByRole('button', { name: 'Faturas de Cartão E2E' }).click()
    await expect(page.getByRole('heading', { name: 'Faturas de Cartão E2E' })).toBeVisible()
    await expect(page.getByText('Carregando faturas...')).toHaveCount(0)
    await capturar(page, projeto, '11-faturas')
  })

  await test.step('seletor de tema', async () => {
    await page.getByRole('button', { name: 'Tema escuro' }).click()
    await expect(page.locator('html')).toHaveAttribute('data-tema', 'escuro')
    await page.getByRole('button', { name: 'Tema claro' }).click()
    await expect(page.locator('html')).toHaveAttribute('data-tema', 'claro')
    await page.getByRole('button', { name: 'Tema sistema' }).click()
    await expect(page.locator('html')).not.toHaveAttribute('data-tema')
  })

  // Uma captura de cada seção, já com os dados do fluxo.
  await test.step('capturas das seções', async () => {
    await irPara(page, 'Início')
    await expect(page.getByRole('list', { name: 'Próximas previstas' })).toBeVisible()
    await capturar(page, projeto, '12-inicio')
    await irPara(page, 'Lançamentos')
    await expect(page.getByRole('region', { name: 'Lançamentos' })).toContainText('Mercado')
    await capturar(page, projeto, '13-lancamentos')
    // O menu de ações abre por teclado ou clique e Escape devolve o foco ao botão.
    const menu = page.getByRole('button', { name: 'Mais ações de Mercado' })
    await menu.click()
    await expect(menu).toHaveAttribute('aria-expanded', 'true')
    await expect(page.getByRole('button', { name: 'Apagar Mercado' })).toBeVisible()
    await capturar(page, projeto, '13-lancamentos-menu')
    await page.keyboard.press('Escape')
    await expect(menu).toHaveAttribute('aria-expanded', 'false')
    await expect(menu).toBeFocused()
    await irPara(page, 'Contas')
    await capturar(page, projeto, '14-contas')
    await irPara(page, 'Recorrências')
    await capturar(page, projeto, '15-recorrencias')
  })

  await test.step('sair', async () => {
    await page.getByRole('button', { name: 'Sair' }).click()
    await expect(page.getByRole('form', { name: 'Entrar' })).toBeVisible()
    await capturar(page, projeto, '16-saiu')
  })
})
