import { useEffect, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { IconePontos } from './icones'

// Menu de ações do item: um botão que abre uma lista de botões. Escape ou clique fora fecham, e o
// foco volta ao botão que abriu. Clicar numa ação também fecha (o clique chega ao container depois).
export default function MenuDeAcoes({ rotulo, children }: { rotulo: string; children: ReactNode }) {
  const [aberto, setAberto] = useState(false)
  const raiz = useRef<HTMLDivElement>(null)
  const gatilho = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    if (!aberto) return
    raiz.current?.querySelector<HTMLElement>('.menu-acoes-lista button')?.focus()
    function aoTeclar(evento: KeyboardEvent): void {
      if (evento.key !== 'Escape') return
      evento.stopPropagation()
      setAberto(false)
      gatilho.current?.focus()
    }
    function aoClicarFora(evento: MouseEvent): void {
      if (evento.target instanceof Node && raiz.current?.contains(evento.target)) return
      setAberto(false)
    }
    document.addEventListener('keydown', aoTeclar)
    document.addEventListener('mousedown', aoClicarFora)
    return () => {
      document.removeEventListener('keydown', aoTeclar)
      document.removeEventListener('mousedown', aoClicarFora)
    }
  }, [aberto])

  return (
    <div className="menu-acoes" ref={raiz}>
      <button
        ref={gatilho}
        type="button"
        className="pequeno icone-botao"
        aria-label={rotulo}
        aria-haspopup="true"
        aria-expanded={aberto}
        onClick={() => setAberto((atual) => !atual)}
      >
        <IconePontos />
      </button>
      {aberto && (
        <div
          className="menu-acoes-lista"
          onClick={() => {
            // O item clicado some com o menu. O foco vai para o botão do menu antes disso, e quem
            // abrir um painel a partir da ação devolve o foco para ele ao fechar.
            gatilho.current?.focus()
            setAberto(false)
          }}
        >
          {children}
        </div>
      )}
    </div>
  )
}
