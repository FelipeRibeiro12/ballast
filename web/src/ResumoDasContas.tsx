import { useEffect, useState } from 'react'
import { fetchPatrimonio } from './api'
import type { Conta, Patrimonio } from './api'
import { hojeLocal } from './datas'
import { formatarCentavos } from './dinheiro'

export type GrupoDoResumo = { chave: string; rotulo: string; centavos: bigint }

function somar(contas: Conta[], tipos: readonly Conta['tipo'][]): bigint {
  return contas
    .filter((conta) => tipos.includes(conta.tipo))
    .reduce((soma, conta) => soma + BigInt(conta.saldoCentavos), 0n)
}

// O total não conta cartão de crédito: fatura aberta não é dinheiro que o usuário tem. Cripto e
// investimentos não têm saldo em reais na conta, então vêm do patrimônio; sem ele os grupos somem e o
// total fica só com as contas em reais. BigInt porque a soma passa do inteiro seguro (ADR-023).
export function gruposDoResumo(contas: Conta[], patrimonio: Patrimonio | null): GrupoDoResumo[] {
  const emReais = somar(contas, ['corrente', 'poupanca', 'cofrinho', 'dinheiro', 'beneficio'])
  const cripto = patrimonio === null ? 0n : BigInt(patrimonio.criptoCentavos)
  const investimentos = patrimonio === null ? 0n : BigInt(patrimonio.rendaFixaCentavos)
  const grupos: GrupoDoResumo[] = [
    { chave: 'total', rotulo: 'Total, sem cartão', centavos: emReais + cripto + investimentos },
    { chave: 'bancos', rotulo: 'Corrente e poupança', centavos: somar(contas, ['corrente', 'poupanca']) },
  ]
  if (contas.some((conta) => conta.tipo === 'cofrinho')) {
    grupos.push({ chave: 'cofrinho', rotulo: 'Cofrinho', centavos: somar(contas, ['cofrinho']) })
  }
  grupos.push(
    { chave: 'dinheiro', rotulo: 'Dinheiro', centavos: somar(contas, ['dinheiro']) },
    { chave: 'beneficio', rotulo: 'Benefício', centavos: somar(contas, ['beneficio']) },
  )
  if (patrimonio !== null) {
    grupos.push(
      { chave: 'cripto', rotulo: 'Cripto', centavos: cripto },
      { chave: 'investimentos', rotulo: 'Investimentos', centavos: investimentos },
      { chave: 'investimentos-cripto', rotulo: 'Investimentos e cripto', centavos: investimentos + cripto },
    )
  }
  return grupos
}

// Carrossel de totais: desliza de lado com scroll-snap, sem biblioteca.
export default function ResumoDasContas({ contas }: { contas: Conta[] }) {
  const [patrimonio, setPatrimonio] = useState<Patrimonio | null>(null)
  const [falhou, setFalhou] = useState(false)

  useEffect(() => {
    let vale = true
    // Sem o patrimônio o resumo ainda serve com as contas em reais, e a tela avisa que faltam os outros.
    fetchPatrimonio(hojeLocal()).then(
      (dados) => {
        if (vale) setPatrimonio(dados)
      },
      () => {
        if (vale) setFalhou(true)
      },
    )
    return () => {
      vale = false
    }
  }, [])

  return (
    <>
    <div className="resumo-carrossel" role="group" aria-label="Totais das contas">
      {gruposDoResumo(contas, patrimonio).map((grupo) => (
        <div key={grupo.chave} className="resumo-grupo">
          <small>{grupo.rotulo}</small>
          <strong>{formatarCentavos(grupo.centavos)}</strong>
        </div>
      ))}
    </div>
    {falhou && <small role="status">Cripto e investimentos indisponíveis agora; o total não os inclui.</small>}
    </>
  )
}
