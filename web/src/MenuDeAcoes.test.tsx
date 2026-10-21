import { fireEvent, render, screen } from '@testing-library/react'
import MenuDeAcoes from './MenuDeAcoes'

function Exemplo({ aoEscolher }: { aoEscolher: () => void }) {
  return (
    <>
      <button type="button">Fora</button>
      <MenuDeAcoes rotulo="Mais ações de Feira">
        <button type="button" onClick={aoEscolher}>
          Editar Feira
        </button>
        <button type="button">Apagar Feira</button>
      </MenuDeAcoes>
    </>
  )
}

describe('MenuDeAcoes', () => {
  it('começa fechado, com aria-haspopup e aria-expanded', () => {
    render(<Exemplo aoEscolher={() => {}} />)
    const gatilho = screen.getByRole('button', { name: 'Mais ações de Feira' })
    expect(gatilho).toHaveAttribute('aria-haspopup', 'true')
    expect(gatilho).toHaveAttribute('aria-expanded', 'false')
    expect(screen.queryByRole('button', { name: 'Editar Feira' })).toBeNull()
  })

  it('abre a lista de botões e leva o foco ao primeiro', () => {
    render(<Exemplo aoEscolher={() => {}} />)
    const gatilho = screen.getByRole('button', { name: 'Mais ações de Feira' })
    fireEvent.click(gatilho)
    expect(gatilho).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getByRole('button', { name: 'Editar Feira' })).toHaveFocus()
    expect(screen.getByRole('button', { name: 'Apagar Feira' })).toBeVisible()
  })

  it('Escape fecha e devolve o foco ao botão do menu', () => {
    render(<Exemplo aoEscolher={() => {}} />)
    const gatilho = screen.getByRole('button', { name: 'Mais ações de Feira' })
    fireEvent.click(gatilho)
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(screen.queryByRole('button', { name: 'Editar Feira' })).toBeNull()
    expect(gatilho).toHaveAttribute('aria-expanded', 'false')
    expect(gatilho).toHaveFocus()
  })

  it('clicar fora fecha', () => {
    render(<Exemplo aoEscolher={() => {}} />)
    fireEvent.click(screen.getByRole('button', { name: 'Mais ações de Feira' }))
    fireEvent.mouseDown(screen.getByRole('button', { name: 'Fora' }))
    expect(screen.queryByRole('button', { name: 'Editar Feira' })).toBeNull()
  })

  it('escolher uma ação executa e fecha o menu', () => {
    const aoEscolher = vi.fn()
    render(<Exemplo aoEscolher={aoEscolher} />)
    fireEvent.click(screen.getByRole('button', { name: 'Mais ações de Feira' }))
    fireEvent.click(screen.getByRole('button', { name: 'Editar Feira' }))
    expect(aoEscolher).toHaveBeenCalledTimes(1)
    expect(screen.queryByRole('button', { name: 'Editar Feira' })).toBeNull()
  })
})
