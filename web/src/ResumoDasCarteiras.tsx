import { useEffect, useState } from 'react'
import { fetchResumoCarteiras } from './api'
import type { ResumoCarteira, ResumoCarteiras, ResumoCarteirasTotal } from './api'
import { formatarCentavos, formatarCentavosEmDolar } from './dinheiro'
import { carregar } from './leitura'
import type { Estado } from './leitura'
import { Carregando } from './ui'
import { formatarVariacaoEmDolar, formatarVariacaoEmReais, formatarVariacaoPercentual, sentidoDaVariacao } from './variacao'

const ROTULO_DA_REDE: Record<ResumoCarteira['rede'], string> = {
  bitcoin: 'Bitcoin',
  ethereum: 'Ethereum',
  base: 'Base',
  arbitrum: 'Arbitrum',
  polygon: 'Polygon',
  avalanche: 'Avalanche',
  robinhood: 'Robinhood Chain',
  solana: 'Solana',
}

function Variacao({ valor }: { valor: ResumoCarteirasTotal }) {
  const sentido = sentidoDaVariacao(valor.variacao24hUsdCentavos)
  const reais =
    valor.variacao24hBrlCentavos === null ? null : formatarVariacaoEmReais(valor.variacao24hBrlCentavos)
  return (
    <p className={`variacao variacao-${sentido}`}>
      <span>24 h: </span>
      <strong>{formatarVariacaoEmDolar(valor.variacao24hUsdCentavos)}</strong>
      {reais !== null && <span> ({reais})</span>}
      <span> · {formatarVariacaoPercentual(valor.variacao24hPercentual)}</span>
      {valor.variacaoParcial && <small className="variacao-parcial"> variação parcial</small>}
    </p>
  )
}

function Valor({ valor }: { valor: ResumoCarteirasTotal }) {
  return (
    <>
      <strong className="item-valor">{formatarCentavosEmDolar(valor.valorUsdCentavos)}</strong>
      {valor.valorBrlCentavos !== null && <small>{formatarCentavos(valor.valorBrlCentavos)}</small>}
    </>
  )
}

function nomeDaCarteira(carteira: ResumoCarteira): string {
  return carteira.rotulo !== null && carteira.rotulo.trim() !== '' ? carteira.rotulo : ROTULO_DA_REDE[carteira.rede]
}

function Conteudo({ resumo }: { resumo: ResumoCarteiras }) {
  // Sem carteira observada não há o que somar: o cadastro logo abaixo já explica o próximo passo.
  if (resumo.carteiras.length === 0) return null
  return (
    <>
      <section className="cartao resumo-carteiras" aria-label="Total das carteiras">
        <p className="destaque-rotulo">Total nas carteiras</p>
        <Valor valor={resumo.total} />
        <Variacao valor={resumo.total} />
      </section>
      <ul className="lancamentos lista-cartao" aria-label="Valor por carteira">
        {resumo.carteiras.map((carteira) => (
          <li key={carteira.id} className="resumo-carteira">
            <div className="item-corpo">
              <strong className="item-titulo">{nomeDaCarteira(carteira)}</strong>
              <span className="item-meta">{ROTULO_DA_REDE[carteira.rede]}</span>
              <Variacao valor={carteira} />
            </div>
            <div className="item-lado">
              <Valor valor={carteira} />
            </div>
          </li>
        ))}
      </ul>
    </>
  )
}

// O resumo é independente do resto da aba: se falhar, só ele mostra o erro.
export default function ResumoDasCarteiras({
  versao,
  aoSessaoExpirar,
}: {
  versao: number
  aoSessaoExpirar: () => void
}) {
  const [estado, setEstado] = useState<Estado<ResumoCarteiras>>({ tipo: 'carregando' })

  useEffect(() => {
    let vale = true
    carregar(fetchResumoCarteiras, 'o valor das carteiras', aoSessaoExpirar, setEstado, () => vale)
    return () => {
      vale = false
    }
  }, [versao, aoSessaoExpirar])

  if (estado.tipo === 'carregando') return <Carregando texto="Carregando valor das carteiras..." linhas={2} />
  if (estado.tipo === 'erro') {
    return (
      <p role="alert" className="erro">
        {estado.mensagem}
      </p>
    )
  }
  return <Conteudo resumo={estado.dados} />
}
