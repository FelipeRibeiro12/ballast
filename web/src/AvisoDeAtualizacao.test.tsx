import { fireEvent, render, screen } from '@testing-library/react'
import { vi } from 'vitest'
import AvisoDeAtualizacao from './AvisoDeAtualizacao'

const mocks = vi.hoisted(() => ({
  precisaAtualizar: false,
  atualizar: vi.fn(),
}))

vi.mock('virtual:pwa-register/react', () => ({
  useRegisterSW: () => ({
    needRefresh: [mocks.precisaAtualizar, vi.fn()],
    offlineReady: [false, vi.fn()],
    updateServiceWorker: mocks.atualizar,
  }),
}))

describe('AvisoDeAtualizacao', () => {
  beforeEach(() => {
    mocks.precisaAtualizar = false
    mocks.atualizar.mockReset()
  })

  it('não mostra nada sem versão nova', () => {
    render(<AvisoDeAtualizacao />)
    expect(screen.queryByText(/nova versão disponível/i)).toBeNull()
    expect(screen.queryByRole('button', { name: /atualizar/i })).toBeNull()
  })

  it('avisa quando há versão nova e o botão atualiza e recarrega', () => {
    mocks.precisaAtualizar = true
    render(<AvisoDeAtualizacao />)
    expect(screen.getByRole('status')).toHaveTextContent(/nova versão disponível/i)
    fireEvent.click(screen.getByRole('button', { name: /atualizar/i }))
    expect(mocks.atualizar).toHaveBeenCalledWith(true)
  })
})
