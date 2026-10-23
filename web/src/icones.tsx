import type { ReactNode } from 'react'

// Todos os ícones são decorativos: o texto ao lado (ou o aria-label do botão) dá o nome.
function Base({ tamanho = 20, children }: { tamanho?: number; children: ReactNode }) {
  return (
    <svg
      viewBox="0 0 24 24"
      width={tamanho}
      height={tamanho}
      fill="none"
      stroke="currentColor"
      strokeWidth={1.75}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
    >
      {children}
    </svg>
  )
}

type Props = { tamanho?: number }

export function IconeInicio({ tamanho }: Props) {
  return (
    <Base tamanho={tamanho}>
      <path d="M3 11.5 12 4l9 7.5" />
      <path d="M5.5 10v9.5h13V10" />
      <path d="M10 19.5v-5h4v5" />
    </Base>
  )
}

export function IconeLancamentos({ tamanho }: Props) {
  return (
    <Base tamanho={tamanho}>
      <path d="M9 6h11M9 12h11M9 18h11" />
      <path d="M4.5 6h.01M4.5 12h.01M4.5 18h.01" strokeWidth={2.5} />
    </Base>
  )
}

export function IconeContas({ tamanho }: Props) {
  return (
    <Base tamanho={tamanho}>
      <path d="M4 7.5A2.5 2.5 0 0 1 6.5 5H18v3" />
      <path d="M4 7.5V17a2 2 0 0 0 2 2h13a1 1 0 0 0 1-1V9a1 1 0 0 0-1-1H6.5A2.5 2.5 0 0 1 4 7.5Z" />
      <path d="M16 13.5h.01" strokeWidth={2.5} />
    </Base>
  )
}

export function IconeRecorrencias({ tamanho }: Props) {
  return (
    <Base tamanho={tamanho}>
      <path d="m17 3 3 3-3 3" />
      <path d="M4 11V9.5A3.5 3.5 0 0 1 7.5 6H20" />
      <path d="m7 21-3-3 3-3" />
      <path d="M20 13v1.5a3.5 3.5 0 0 1-3.5 3.5H4" />
    </Base>
  )
}

export function IconeOrcamento({ tamanho }: Props) {
  return (
    <Base tamanho={tamanho}>
      <path d="M12 3a9 9 0 1 0 9 9h-9z" />
      <path d="M15 3.5A9 9 0 0 1 20.5 9H15z" />
    </Base>
  )
}

export function IconeBeneficio({ tamanho }: Props) {
  return (
    <Base tamanho={tamanho}>
      <path d="M3 9V7a1 1 0 0 1 1-1h16a1 1 0 0 1 1 1v2a2.5 2.5 0 0 0 0 5v3a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1v-3a2.5 2.5 0 0 0 0-5Z" />
      <path d="M14 8.5v1M14 11.5v1M14 14.5v1" />
    </Base>
  )
}

export function IconeMais({ tamanho }: Props) {
  return (
    <Base tamanho={tamanho}>
      <path d="M12 5v14M5 12h14" />
    </Base>
  )
}

export function IconeFechar({ tamanho }: Props) {
  return (
    <Base tamanho={tamanho}>
      <path d="m6 6 12 12M18 6 6 18" />
    </Base>
  )
}

export function IconeSetaEsquerda({ tamanho }: Props) {
  return (
    <Base tamanho={tamanho}>
      <path d="m15 5-7 7 7 7" />
    </Base>
  )
}

export function IconeSetaDireita({ tamanho }: Props) {
  return (
    <Base tamanho={tamanho}>
      <path d="m9 5 7 7-7 7" />
    </Base>
  )
}

export function IconeSol({ tamanho }: Props) {
  return (
    <Base tamanho={tamanho}>
      <circle cx="12" cy="12" r="4" />
      <path d="M12 2.5v2M12 19.5v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2.5 12h2M19.5 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" />
    </Base>
  )
}

export function IconeLua({ tamanho }: Props) {
  return (
    <Base tamanho={tamanho}>
      <path d="M20 14.5A8 8 0 0 1 9.5 4 8 8 0 1 0 20 14.5Z" />
    </Base>
  )
}

export function IconeMonitor({ tamanho }: Props) {
  return (
    <Base tamanho={tamanho}>
      <rect x="3" y="4" width="18" height="12" rx="2" />
      <path d="M8 20h8M12 16v4" />
    </Base>
  )
}

export function IconeSair({ tamanho }: Props) {
  return (
    <Base tamanho={tamanho}>
      <path d="M10 4H6a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h4" />
      <path d="m16 8 4 4-4 4M20 12H9" />
    </Base>
  )
}

export function IconeCartao({ tamanho }: Props) {
  return (
    <Base tamanho={tamanho}>
      <rect x="2.5" y="5" width="19" height="14" rx="2.5" />
      <path d="M2.5 10h19M6.5 15h4" />
    </Base>
  )
}

export function IconeBanco({ tamanho }: Props) {
  return (
    <Base tamanho={tamanho}>
      <path d="M3 9.5 12 4l9 5.5" />
      <path d="M5 10v7M9.5 10v7M14.5 10v7M19 10v7M3.5 20h17" />
    </Base>
  )
}

export function IconeDinheiro({ tamanho }: Props) {
  return (
    <Base tamanho={tamanho}>
      <rect x="2.5" y="6" width="19" height="12" rx="2" />
      <circle cx="12" cy="12" r="2.5" />
    </Base>
  )
}

export function IconeCripto({ tamanho }: Props) {
  return (
    <Base tamanho={tamanho}>
      <circle cx="12" cy="12" r="8.5" />
      <path d="M9.5 8h3.75a2 2 0 0 1 0 4H9.5h4.25a2 2 0 0 1 0 4H9.5V8Z" />
      <path d="M11 6.5V8M11 16v1.5M13 6.5V8M13 16v1.5" />
    </Base>
  )
}

export function IconeInvestimentos({ tamanho }: Props) {
  return (
    <Base tamanho={tamanho}>
      <path d="M4 19.5h16" />
      <path d="M6 16v-3M10.5 16V9M15 16v-5M19.5 16V6" />
      <path d="m5 9 4.5-4 4 3L20 3" />
    </Base>
  )
}

export function IconeSetaCima({ tamanho }: Props) {
  return (
    <Base tamanho={tamanho}>
      <path d="M12 19V5M6 11l6-6 6 6" />
    </Base>
  )
}

export function IconeSetaBaixo({ tamanho }: Props) {
  return (
    <Base tamanho={tamanho}>
      <path d="M12 5v14M6 13l6 6 6-6" />
    </Base>
  )
}

export function IconeTroca({ tamanho }: Props) {
  return (
    <Base tamanho={tamanho}>
      <path d="m7 4-4 4 4 4M3 8h14" />
      <path d="m17 12 4 4-4 4M21 16H7" />
    </Base>
  )
}

export function IconeAlerta({ tamanho }: Props) {
  return (
    <Base tamanho={tamanho}>
      <path d="M12 3.5 2.5 20h19L12 3.5Z" />
      <path d="M12 10v4.5M12 17.5h.01" />
    </Base>
  )
}

export function IconeRelogio({ tamanho }: Props) {
  return (
    <Base tamanho={tamanho}>
      <circle cx="12" cy="12" r="9" />
      <path d="M12 7v5l3 2" />
    </Base>
  )
}

export function IconeVazio({ tamanho = 40 }: Props) {
  return (
    <Base tamanho={tamanho}>
      <rect x="4" y="5" width="16" height="14" rx="3" />
      <path d="M8 10h8M8 14h5" />
    </Base>
  )
}

// Marca do app: ondas dentro de um quadrado arredondado, na cor primária do tema.
export function Logo({ tamanho = 28 }: Props) {
  return (
    <svg viewBox="0 0 32 32" width={tamanho} height={tamanho} aria-hidden="true" focusable="false">
      <rect width="32" height="32" rx="9" fill="var(--primaria)" />
      <path
        d="M7 18.5c2.2 0 2.8-4 5-4s2.8 4 5 4 2.8-4 5-4"
        fill="none"
        stroke="var(--sobre-primaria)"
        strokeWidth={2.4}
        strokeLinecap="round"
      />
      <path
        d="M7 23c2.2 0 2.8-2.5 5-2.5S14.8 23 17 23s2.8-2.5 5-2.5"
        fill="none"
        stroke="var(--sobre-primaria)"
        strokeWidth={2.4}
        strokeLinecap="round"
        opacity={0.55}
      />
    </svg>
  )
}

export function IconePontos({ tamanho }: Props) {
  return (
    <Base tamanho={tamanho}>
      <path d="M5 12h.01M12 12h.01M19 12h.01" strokeWidth={3} />
    </Base>
  )
}
