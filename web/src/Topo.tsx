import { useState } from 'react'
import type { ReactNode } from 'react'
import { postLogout } from './api'
import {
  IconeContas,
  IconeInvestimentos,
  IconeInicio,
  IconeLancamentos,
  IconeLua,
  IconeMonitor,
  IconeRecorrencias,
  IconeSair,
  IconeSol,
  Logo,
} from './icones'
import { ROTULO_DA_SECAO, SECOES, hashDaSecao } from './rota'
import type { Secao, SecaoDaBarra } from './rota'
import { ROTULO_DO_TEMA, TEMAS, useTema } from './tema'
import type { Tema } from './tema'

const ICONE_DA_SECAO: Record<SecaoDaBarra, ReactNode> = {
  inicio: <IconeInicio />,
  lancamentos: <IconeLancamentos />,
  contas: <IconeContas />,
  recorrencias: <IconeRecorrencias />,
  investimentos: <IconeInvestimentos />,
}

const ICONE_DO_TEMA: Record<Tema, ReactNode> = {
  claro: <IconeSol tamanho={18} />,
  escuro: <IconeLua tamanho={18} />,
  sistema: <IconeMonitor tamanho={18} />,
}

function SeletorDeTema() {
  const [tema, escolher] = useTema()
  return (
    <div role="group" aria-label="Tema" className="segmentos">
      {TEMAS.map((opcao) => (
        <button
          key={opcao}
          type="button"
          aria-pressed={opcao === tema}
          aria-label={`Tema ${ROTULO_DO_TEMA[opcao].toLowerCase()}`}
          title={ROTULO_DO_TEMA[opcao]}
          onClick={() => escolher(opcao)}
        >
          {ICONE_DO_TEMA[opcao]}
        </button>
      ))}
    </div>
  )
}

// Um só <nav>: o CSS põe a barra embaixo no celular e no topo a partir de 768px.
export default function Topo({ secao, aoSair }: { secao: Secao; aoSair: () => void }) {
  const [saindo, setSaindo] = useState(false)
  const [falhou, setFalhou] = useState(false)

  async function sair(): Promise<void> {
    setSaindo(true)
    setFalhou(false)
    try {
      await postLogout()
      aoSair()
    } catch {
      // Sem confirmação do servidor a sessão pode seguir viva: continua logado e avisa.
      setFalhou(true)
      setSaindo(false)
    }
  }

  return (
    <header className="topo">
      <div className="topo-interno">
        <h1 className="marca-app">
          <Logo />
          Ballast
        </h1>
        <nav className="nav" aria-label="Seções">
          {SECOES.map((destino) => (
            <a
              key={destino}
              href={hashDaSecao(destino)}
              aria-current={destino === secao ? 'page' : undefined}
            >
              <span className="nav-icone">{ICONE_DA_SECAO[destino]}</span>
              {ROTULO_DA_SECAO[destino]}
            </a>
          ))}
        </nav>
        <div className="topo-acoes">
          <SeletorDeTema />
          <button type="button" className="pequeno" disabled={saindo} onClick={() => void sair()}>
            <IconeSair tamanho={16} />
            Sair
          </button>
        </div>
      </div>
      {falhou && (
        <p role="alert" className="erro topo-erro">
          Não foi possível sair. Tente de novo.
        </p>
      )}
    </header>
  )
}
