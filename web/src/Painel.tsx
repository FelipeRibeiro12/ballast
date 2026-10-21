import { useEffect, useId, useRef } from 'react'
import type { KeyboardEvent, ReactNode } from 'react'
import { IconeFechar } from './icones'

const FOCAVEIS =
  'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'

// Painel de formulário: gaveta lateral no desktop e folha vinda de baixo no celular.
// Renderiza no lugar (sem portal) para ficar dentro da região da seção que o abriu.
export default function Painel({
  titulo,
  aoFechar,
  children,
}: {
  titulo: string
  aoFechar: () => void
  children: ReactNode
}) {
  const idDoTitulo = useId()
  const caixa = useRef<HTMLDivElement>(null)
  // O pai redesenha a cada tecla; o efeito de foco não pode rodar de novo por causa de uma função nova.
  const fechar = useRef(aoFechar)
  useEffect(() => {
    fechar.current = aoFechar
  }, [aoFechar])

  useEffect(() => {
    const anterior = document.activeElement
    const bloqueio = document.body.classList
    bloqueio.add('painel-aberto')
    // Foca o primeiro campo: quem abre o painel quer digitar.
    const primeiro = caixa.current?.querySelector<HTMLElement>('input, select, textarea')
    primeiro?.focus()
    return () => {
      bloqueio.remove('painel-aberto')
      if (anterior instanceof HTMLElement) anterior.focus()
    }
  }, [])

  function aoTeclar(evento: KeyboardEvent<HTMLDivElement>): void {
    if (evento.key === 'Escape') {
      evento.stopPropagation()
      fechar.current()
      return
    }
    if (evento.key !== 'Tab' || caixa.current === null) return
    // Mantém o Tab dentro do painel enquanto ele está aberto.
    const itens = Array.from(caixa.current.querySelectorAll<HTMLElement>(FOCAVEIS))
    if (itens.length === 0) return
    const primeiro = itens[0]
    const ultimo = itens[itens.length - 1]
    if (evento.shiftKey && document.activeElement === primeiro) {
      evento.preventDefault()
      ultimo.focus()
    } else if (!evento.shiftKey && document.activeElement === ultimo) {
      evento.preventDefault()
      primeiro.focus()
    }
  }

  return (
    <div
      className="painel-fundo"
      // Clique no fundo escuro fecha; o teclado fecha com Escape e pelo botão Fechar.
      onMouseDown={(evento) => {
        if (evento.target === evento.currentTarget) fechar.current()
      }}
    >
      <div
        ref={caixa}
        className="painel"
        role="dialog"
        aria-modal="true"
        aria-labelledby={idDoTitulo}
        onKeyDown={aoTeclar}
      >
        <header className="painel-topo">
          <h3 id={idDoTitulo}>{titulo}</h3>
          <button type="button" className="icone-botao" aria-label="Fechar painel" onClick={() => fechar.current()}>
            <IconeFechar />
          </button>
        </header>
        <div className="painel-corpo">{children}</div>
      </div>
    </div>
  )
}
