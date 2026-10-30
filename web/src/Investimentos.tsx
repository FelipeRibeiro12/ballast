import { useCallback, useEffect, useState } from 'react'
import { fetchPatrimonio } from './api'
import type { Conta, Patrimonio } from './api'
import Cripto from './Cripto'
import { formatarDataCompleta, hojeLocal } from './datas'
import { formatarCentavos } from './dinheiro'
import { carregar } from './leitura'
import type { Estado } from './leitura'
import RendaFixa from './RendaFixa'
import { Carregando } from './ui'

type Aba = 'renda-fixa' | 'cripto'

function ResumoDoPatrimonio({ estado }: { estado: Estado<Patrimonio> }) {
  if (estado.tipo === 'carregando') return <Carregando texto="Carregando patrimônio..." linhas={2} />
  if (estado.tipo === 'erro') {
    return (
      <p role="alert" className="erro">
        {estado.mensagem}
      </p>
    )
  }
  const { dados } = estado
  const semCotacao = dados.semCotacao.length
  const rendaFixa = dados.rendaFixaCentavos + dados.cofrinhosCentavos
  return (
    <section className="cartao destaque" aria-label="Total investido">
      <p className="destaque-rotulo">Investido em {formatarDataCompleta(dados.data)}</p>
      {/* Renda fixa, cofrinhos que o usuário marcou e cripto. O saldo das contas correntes e dos
          benefícios não é investimento, e o cofrinho só entra se for marcado (ADR-076). */}
      <p className="destaque-valor">{formatarCentavos(rendaFixa + dados.criptoCentavos)}</p>
      <dl className="destaque-detalhes">
        <div>
          <dt>Renda fixa</dt>
          <dd>{formatarCentavos(rendaFixa)}</dd>
        </div>
        <div>
          <dt>Cripto</dt>
          <dd>{formatarCentavos(dados.criptoCentavos)}</dd>
        </div>
      </dl>
      {dados.criptoCentavos !== 0 && dados.criptoAportadoCentavos !== null && dados.criptoValorizacaoCentavos !== null && (
        <p>
          Na cripto, {formatarCentavos(dados.criptoAportadoCentavos)} de aporte e{' '}
          {dados.criptoValorizacaoCentavos < 0
            ? `${formatarCentavos(-dados.criptoValorizacaoCentavos)} de desvalorização`
            : `${formatarCentavos(dados.criptoValorizacaoCentavos)} de valorização`}
          .
        </p>
      )}
      {semCotacao > 0 && (
        <p role="status">
          {semCotacao === 1
            ? '1 posição sem cotação ficou fora do total.'
            : `${semCotacao} posições sem cotação ficaram fora do total.`}
        </p>
      )}
    </section>
  )
}

// Patrimônio no topo, com a data que vale para as duas abas; renda fixa e cripto embaixo.
export default function Investimentos({
  contasDeInvestimento,
  contasCripto,
  cofrinhosAtivos,
  aoMudarCofrinho,
  aoSessaoExpirar,
}: {
  contasDeInvestimento: Conta[]
  contasCripto: Conta[]
  cofrinhosAtivos: Conta[]
  aoMudarCofrinho: () => void
  aoSessaoExpirar: () => void
}) {
  const [data, setData] = useState(hojeLocal())
  const [aba, setAba] = useState<Aba>('renda-fixa')
  const [versao, setVersao] = useState(0)
  const [patrimonio, setPatrimonio] = useState<Estado<Patrimonio>>({ tipo: 'carregando' })

  // O campo de data fica vazio enquanto o usuário apaga para digitar outra; aí não busca nada.
  const dataValida = /^\d{4}-\d{2}-\d{2}$/.test(data)

  useEffect(() => {
    if (!dataValida) return
    let vale = true
    carregar(() => fetchPatrimonio(data), 'o patrimônio', aoSessaoExpirar, setPatrimonio, () => vale)
    return () => {
      vale = false
    }
  }, [data, dataValida, versao, aoSessaoExpirar])

  // Estável: as abas o chamam depois de cada mudança.
  const aoMudar = useCallback((): void => setVersao((n) => n + 1), [])

  return (
    <section aria-labelledby="titulo-investimentos">
      <header className="pagina-topo">
        <h2 id="titulo-investimentos">Investimentos</h2>
      </header>
      <div className="campo campo-data">
        <label htmlFor="investimentos-data">Posição em</label>
        <input
          id="investimentos-data"
          type="date"
          min="1900-01-01"
          max="2199-12-31"
          value={data}
          onChange={(evento) => {
            setData(evento.target.value)
            setPatrimonio({ tipo: 'carregando' })
          }}
        />
      </div>
      {/* A margem fica no contêiner do resumo: o cartão destacado não tem margem própria e colava nas abas. */}
      <div className="investimentos-resumo">
        {dataValida ? <ResumoDoPatrimonio estado={patrimonio} /> : <p role="status">Escolha uma data.</p>}
      </div>
      <div className="segmentos" role="group" aria-label="Tipo de investimento">
        <button type="button" aria-pressed={aba === 'renda-fixa'} onClick={() => setAba('renda-fixa')}>
          Renda fixa
        </button>
        <button type="button" aria-pressed={aba === 'cripto'} onClick={() => setAba('cripto')}>
          Cripto
        </button>
      </div>
      {/* key pela data: cada aba começa do zero quando a data muda, sem efeito para limpar estado. */}
      {dataValida && aba === 'renda-fixa' && (
        <RendaFixa
          key={data}
          contas={contasDeInvestimento}
          cofrinhosAtivos={cofrinhosAtivos}
          cofrinhosNaData={patrimonio.tipo === 'ok' ? patrimonio.dados.cofrinhos : []}
          data={data}
          aoMudar={aoMudar}
          aoMudarCofrinho={aoMudarCofrinho}
          aoSessaoExpirar={aoSessaoExpirar}
        />
      )}
      {dataValida && aba === 'cripto' && (
        <Cripto key={data} contas={contasCripto} data={data} aoMudar={aoMudar} aoSessaoExpirar={aoSessaoExpirar} />
      )}
    </section>
  )
}
