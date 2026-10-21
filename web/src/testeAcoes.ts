import { fireEvent, screen, waitFor } from '@testing-library/react'

// As ações secundárias de cada item (Editar, Apagar, Cancelar...) moram no menu "Mais ações de ...".
// Abre os menus que ainda estão fechados e devolve o botão pedido.
export async function acao(nome: string | RegExp): Promise<HTMLElement> {
  return waitFor(() => {
    const direto = screen.queryByRole('button', { name: nome })
    if (direto !== null) return direto
    for (const menu of screen.queryAllByRole('button', { name: /^Mais ações de / })) {
      if (menu.getAttribute('aria-expanded') === 'false') fireEvent.click(menu)
    }
    const aberto = screen.queryByRole('button', { name: nome })
    if (aberto === null) throw new Error(`ação não encontrada: ${String(nome)}`)
    return aberto
  })
}

// Para asserções de ausência: só vale se os menus estiverem abertos.
export function abrirMenus(): void {
  for (const menu of screen.queryAllByRole('button', { name: /^Mais ações de / })) {
    if (menu.getAttribute('aria-expanded') === 'false') fireEvent.click(menu)
  }
}
