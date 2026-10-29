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
  return new RegExp(`R\\$\\s${texto.replace(/[\\^$.*+?()[\]{}|]/g, '\\$&')}`)
}

type Secao = 'Início' | 'Lançamentos' | 'Contas' | 'Recorrências' | 'Investimentos'

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

  await test.step('vale-benefício com Alimentação permitida', async () => {
    const novaConta = await abrirNovaConta()
    await novaConta.getByLabel('Nome').fill('VR E2E')
    await novaConta.getByLabel('Tipo').selectOption({ label: 'Vale-benefício' })
    await novaConta.getByLabel('Saldo inicial em reais (opcional)').fill('500,00')
    await novaConta.getByRole('checkbox', { name: 'Alimentação' }).check()
    await capturar(page, projeto, '04a-novo-beneficio')
    await novaConta.getByRole('button', { name: 'Criar conta' }).click()
    await expect(painel).toHaveCount(0)
    await expect(conta('VR E2E')).toContainText(reais('500,00'))
    await expect(conta('VR E2E').getByRole('list', { name: 'Categorias permitidas em VR E2E' })).toContainText('Alimentação')
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

  await test.step('despesa de Alimentação no vale-benefício', async () => {
    await abrirNovoLancamento()
    await lancamento.getByLabel('Título').fill('Almoço')
    await lancamento.getByLabel('Valor em reais').fill('50,00')
    await lancamento.getByLabel('Conta').selectOption({ label: 'VR E2E' })
    // O select só oferece o que a conta aceita e o campo pede a escolha.
    const categoria = lancamento.getByLabel('Categoria')
    await expect(categoria.locator('option:not([disabled])')).toHaveText(['Alimentação'])
    await categoria.selectOption({ label: 'Alimentação' })
    await capturar(page, projeto, '06a-despesa-beneficio')
    await lancamento.getByRole('button', { name: 'Adicionar lançamento' }).click()
    await expect(painel).toHaveCount(0)
    await irPara(page, 'Contas')
    await expect(conta('VR E2E')).toContainText(reais('450,00'))
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
    // Benefício não participa de transferência.
    await expect(transferencia.getByLabel('Conta de origem').locator('option', { hasText: 'VR E2E' })).toHaveCount(0)
    await expect(transferencia.getByLabel('Conta de destino').locator('option', { hasText: 'VR E2E' })).toHaveCount(0)
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
    await expect(page.getByRole('region', { name: 'Resumo das contas' })).toContainText('Total sem cartão')
    await capturar(page, projeto, '10-saldo-projetado')
  })

  await test.step('faturas do cartão', async () => {
    await irPara(page, 'Contas')
    await page.getByRole('button', { name: 'Faturas de Cartão E2E' }).click()
    await expect(page.getByRole('heading', { name: 'Faturas de Cartão E2E' })).toBeVisible()
    await expect(page.getByText('Carregando faturas...')).toHaveCount(0)
    await capturar(page, projeto, '11-faturas')
  })

  await test.step('cripto manual', async () => {
    await irPara(page, 'Contas')
    const novaConta = await abrirNovaConta()
    await novaConta.getByLabel('Nome').fill('Exchange E2E')
    await novaConta.getByLabel('Tipo').selectOption('cripto')
    await expect(novaConta.getByLabel('Saldo inicial em reais (opcional)')).toHaveCount(0)
    await novaConta.getByRole('button', { name: 'Criar conta' }).click()
    await expect(painel).toHaveCount(0)
    await expect(conta('Exchange E2E').getByRole('link', { name: 'Ver posições' })).toBeVisible()

    await irPara(page, 'Investimentos')
    await page.getByRole('button', { name: 'Cripto', exact: true }).click()
    await expect(page.getByText(/nunca pede frase de recuperação/)).toBeVisible()
    await page.getByRole('button', { name: 'Novo ativo' }).click()
    const ativo = page.getByRole('form', { name: 'Novo ativo' })
    await ativo.getByLabel('Símbolo').fill('BTC')
    await ativo.getByLabel('Nome (opcional)').fill('Bitcoin')
    await capturar(page, projeto, '11a-novo-ativo')
    await ativo.getByRole('button', { name: 'Cadastrar ativo' }).click()
    await expect(painel).toHaveCount(0)

    await page.getByRole('button', { name: 'Novo movimento' }).click()
    const movimento = page.getByRole('form', { name: 'Novo movimento' })
    await movimento.getByLabel('Quantidade').fill('0,5')
    await movimento.getByLabel('Data').fill('2026-01-10')
    await movimento.getByLabel('Quanto pagou, em dólares').fill('30.000,00')
    await movimento.getByRole('button', { name: 'Registrar compra' }).click()
    await expect(painel).toHaveCount(0)
    const posicoes = page.getByRole('list', { name: 'Posições' })
    await expect(posicoes).toContainText('0,5 BTC')
    await expect(posicoes).toContainText('Sem cotação')

    await posicoes.getByRole('button', { name: 'Informar cotação de BTC' }).click()
    await page.getByLabel('Preço de 1 BTC em dólares').fill('80.000,00')
    await page.getByRole('button', { name: 'Salvar cotação' }).click()
    await expect(painel).toHaveCount(0)
    // O valor em reais depende da PTAX do dia (ADR-038); o em dólar não.
    await expect(posicoes).toContainText(/US\$\s40\.000,00/)
    await expect(page.getByRole('region', { name: 'Patrimônio' })).toContainText('Cripto')
    await capturar(page, projeto, '11b-cripto')
  })

  await test.step('renda fixa', async () => {
    await irPara(page, 'Contas')
    const novaConta = await abrirNovaConta()
    await novaConta.getByLabel('Nome').fill('Corretora E2E')
    await novaConta.getByLabel('Tipo').selectOption('investimento')
    await novaConta.getByRole('button', { name: 'Criar conta' }).click()
    await expect(painel).toHaveCount(0)
    await expect(conta('Corretora E2E').getByRole('link', { name: 'Ver títulos' })).toBeVisible()

    await irPara(page, 'Investimentos')
    await expect(page.getByRole('region', { name: 'Renda fixa' })).toBeVisible()
    await page.getByRole('button', { name: 'Novo título' }).click()
    const titulo = page.getByRole('form', { name: 'Novo título' })
    await titulo.getByLabel('Nome').fill('CDB E2E')
    await titulo.getByLabel('Valor aplicado em reais').fill('1.000,00')
    await titulo.getByLabel('Quanto do CDI, em %').fill('100')
    await capturar(page, projeto, '11d-novo-titulo')
    await titulo.getByRole('button', { name: 'Cadastrar título' }).click()
    await expect(painel).toHaveCount(0)
    const titulos = page.getByRole('list', { name: 'Títulos' })
    // Aplicado hoje: sem dia útil corrido, o valor ainda é o aplicado.
    await expect(titulos).toContainText('CDB E2E')
    await expect(titulos).toContainText(reais('1.000,00'))
    await expect(page.getByRole('region', { name: 'Patrimônio' })).toContainText('Renda fixa')
    await capturar(page, projeto, '11e-renda-fixa')
  })

  await test.step('editar e apagar conta e recorrência', async () => {
    await irPara(page, 'Contas')
    const novaConta = await abrirNovaConta()
    await novaConta.getByLabel('Nome').fill('Temporária E2E')
    await novaConta.getByRole('button', { name: 'Criar conta' }).click()
    await expect(painel).toHaveCount(0)
    await page.getByRole('button', { name: 'Editar Temporária E2E' }).click()
    const edicao = page.getByRole('form', { name: 'Editar Temporária E2E' })
    await edicao.getByLabel('Nome').fill('Provisória E2E')
    await edicao.getByLabel('Saldo inicial em reais').fill('50,00')
    await edicao.getByRole('button', { name: 'Salvar' }).click()
    await expect(painel).toHaveCount(0)
    await expect(conta('Provisória E2E')).toContainText(reais('50,00'))
    await page.getByRole('button', { name: 'Apagar Provisória E2E' }).click()
    const exclusao = page.getByRole('form', { name: 'Apagar Provisória E2E' })
    await expect(exclusao).toContainText('0 lançamentos')
    await exclusao.getByLabel('Digite o nome da conta para confirmar').fill('Provisória E2E')
    await capturar(page, projeto, '11c-apagar-conta')
    await exclusao.getByRole('button', { name: 'Apagar de vez' }).click()
    await expect(painel).toHaveCount(0)
    await expect(conta('Provisória E2E')).toHaveCount(0)

    await irPara(page, 'Recorrências')
    await page.getByRole('button', { name: 'Nova recorrência' }).click()
    const recorrencia = page.getByRole('form', { name: 'Nova recorrência' })
    await recorrencia.getByLabel('Título').fill('Academia')
    await recorrencia.getByLabel('Valor em reais').fill('99,90')
    await recorrencia.getByLabel('Conta').selectOption({ label: 'Corrente E2E' })
    await recorrencia.getByRole('button', { name: 'Adicionar recorrência' }).click()
    await expect(painel).toHaveCount(0)
    await page.getByRole('button', { name: 'Editar Academia' }).click()
    const editarRecorrencia = page.getByRole('form', { name: 'Editar Academia' })
    await editarRecorrencia.getByLabel('Valor em reais').fill('129,90')
    await editarRecorrencia.getByRole('button', { name: 'Salvar alterações' }).click()
    await expect(painel).toHaveCount(0)
    const secaoRecorrencias = page.getByRole('region', { name: 'Recorrências' })
    await expect(secaoRecorrencias.getByRole('listitem').filter({ hasText: 'Academia' })).toContainText(reais('129,90'))
    await page.getByRole('button', { name: 'Apagar Academia' }).click()
    await page.getByRole('button', { name: 'Confirmar apagar Academia' }).click()
    await expect(secaoRecorrencias.getByRole('listitem').filter({ hasText: 'Academia' })).toHaveCount(0)
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
    // O vale aparece dentro do Posso gastar, sem cartão próprio.
    await expect(page.getByRole('list', { name: 'Benefícios' })).toBeVisible()
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
