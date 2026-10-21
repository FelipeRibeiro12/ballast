import { fireEvent, render, screen } from '@testing-library/react'
import { useState } from 'react'
import Painel from './Painel'

function Exemplo() {
  const [aberto, setAberto] = useState(false)
  return (
    <>
      <button type="button" onClick={() => setAberto(true)}>
        Abrir
      </button>
      {aberto && (
        <Painel titulo="Novo exemplo" aoFechar={() => setAberto(false)}>
          <label htmlFor="campo">Campo</label>
          <input id="campo" />
          <button type="button">Salvar</button>
        </Painel>
      )}
    </>
  )
}

describe('Painel', () => {
  it('abre como diálogo nomeado pelo título e foca o primeiro campo', () => {
    render(<Exemplo />)
    fireEvent.click(screen.getByRole('button', { name: 'Abrir' }))
    expect(screen.getByRole('dialog', { name: 'Novo exemplo' })).toHaveAttribute('aria-modal', 'true')
    expect(screen.getByLabelText('Campo')).toHaveFocus()
  })

  it('Escape fecha e devolve o foco a quem abriu', () => {
    render(<Exemplo />)
    const abrir = screen.getByRole('button', { name: 'Abrir' })
    abrir.focus()
    fireEvent.click(abrir)
    fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Escape' })
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(abrir).toHaveFocus()
  })

  it('o botão Fechar painel fecha', () => {
    render(<Exemplo />)
    fireEvent.click(screen.getByRole('button', { name: 'Abrir' }))
    fireEvent.click(screen.getByRole('button', { name: 'Fechar painel' }))
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('clicar no fundo fecha, clicar dentro não', () => {
    render(<Exemplo />)
    fireEvent.click(screen.getByRole('button', { name: 'Abrir' }))
    fireEvent.mouseDown(screen.getByLabelText('Campo'))
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    const fundo = screen.getByRole('dialog').parentElement as HTMLElement
    fireEvent.mouseDown(fundo)
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('o Tab no último elemento volta ao primeiro, e Shift+Tab no primeiro vai ao último', () => {
    render(<Exemplo />)
    fireEvent.click(screen.getByRole('button', { name: 'Abrir' }))
    const fechar = screen.getByRole('button', { name: 'Fechar painel' })
    const salvar = screen.getByRole('button', { name: 'Salvar' })
    salvar.focus()
    fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Tab' })
    expect(fechar).toHaveFocus()
    fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Tab', shiftKey: true })
    expect(salvar).toHaveFocus()
  })

  it('trava a rolagem da página enquanto está aberto', () => {
    render(<Exemplo />)
    fireEvent.click(screen.getByRole('button', { name: 'Abrir' }))
    expect(document.body).toHaveClass('painel-aberto')
    fireEvent.click(screen.getByRole('button', { name: 'Fechar painel' }))
    expect(document.body).not.toHaveClass('painel-aberto')
  })
})
