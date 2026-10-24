import { useEffect, useState } from 'react'
import { fetchPossoGastar } from './api'
import type { PossoGastar as Resposta } from './api'
import { formatarDiaMes } from './datas'
import { formatarCentavos } from './dinheiro'
import type { Estado } from './leitura'
import { carregar } from './leitura'
import { Carregando } from './ui'

function ate(resposta: Resposta): string {
  const dia = resposta.ate === resposta.hoje ? 'hoje' : formatarDiaMes(resposta.ate)
  return resposta.proximoSalario === null ? `até ${dia}, fim do mês` : `até ${dia}, véspera do salário`
}

function Vencidas({ centavos }: { centavos: number }) {
  if (centavos === 0) return null
  const valor = formatarCentavos(Math.abs(centavos))
  return (
    <p className="posso-nota">
      {centavos < 0
        ? `Já desconta ${valor} de despesas vencidas que ainda não foram pagas.`
        : `Conta com ${valor} de receitas vencidas que ainda não entraram.`}
    </p>
  )
}

// A pergunta que faz o app ser aberto todo dia (ADR-048): o menor saldo projetado até a véspera do
// próximo salário. O benefício fica à parte porque só paga as categorias dele.
export default function PossoGastar({ versao, aoSessaoExpirar }: { versao: number; aoSessaoExpirar: () => void }) {
  const [estado, setEstado] = useState<Estado<Resposta>>({ tipo: 'carregando' })

  useEffect(() => {
    let vale = true
    carregar(fetchPossoGastar, 'quanto dá para gastar', aoSessaoExpirar, setEstado, () => vale)
    return () => {
      vale = false
    }
  }, [versao, aoSessaoExpirar])

  return (
    <section className="cartao posso-gastar" aria-label="Posso gastar">
      <h3 className="titulo-cartao">Posso gastar</h3>
      {estado.tipo === 'carregando' && <Carregando texto="Calculando quanto dá para gastar..." linhas={2} />}
      {estado.tipo === 'erro' && (
        <p role="alert" className="erro">
          Não foi possível calcular quanto dá para gastar.
        </p>
      )}
      {estado.tipo === 'ok' && <Resultado resposta={estado.dados} />}
    </section>
  )
}

function Resultado({ resposta }: { resposta: Resposta }) {
  const falta = resposta.livreCentavos < 0
  return (
    <>
      <p className="patrimonio-atual">
        {falta ? (
          <strong className="despesa">Faltam {formatarCentavos(-resposta.livreCentavos)}</strong>
        ) : (
          <strong>{formatarCentavos(resposta.livreCentavos)}</strong>
        )}
        <small>
          {falta
            ? `para cobrir o que está previsto até ${formatarDiaMes(resposta.ate)}. O saldo fica negativo em ${formatarDiaMes(resposta.menorSaldoEm)}.`
            : ate(resposta)}
        </small>
      </p>
      {resposta.porDiaCentavos !== null && resposta.dias > 1 && (
        <p className="posso-por-dia">
          {formatarCentavos(resposta.porDiaCentavos)} por dia nos próximos {resposta.dias} dias
        </p>
      )}
      {!falta && resposta.menorSaldoEm !== resposta.hoje && (
        <p className="posso-nota">O saldo chega ao menor valor em {formatarDiaMes(resposta.menorSaldoEm)}.</p>
      )}
      {resposta.proximoSalario === null && (
        <p className="posso-nota">Nenhum salário previsto. Cadastre a recorrência na categoria Salário.</p>
      )}
      <Vencidas centavos={resposta.vencidasCentavos} />
      {resposta.beneficios.length > 0 && (
        <div className="posso-beneficios">
          <ul aria-label="Benefícios">
            {resposta.beneficios.map((beneficio) => (
              <li key={beneficio.contaId}>
                <span>{beneficio.nome}</span>
                <strong className="valor">{formatarCentavos(beneficio.livreCentavos)}</strong>
              </li>
            ))}
          </ul>
          <p className="posso-nota">Só nas categorias de cada benefício.</p>
        </div>
      )}
    </>
  )
}
