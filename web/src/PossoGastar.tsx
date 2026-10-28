import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { SessaoExpiradaError, fetchPossoGastar, putPossoGastarConfiguracao } from './api'
import type { PossoGastar as Resposta } from './api'
import { formatarDiaMes } from './datas'
import { formatarCentavos } from './dinheiro'
import type { Estado } from './leitura'
import { carregar } from './leitura'
import { IconeEngrenagem } from './icones'
import Painel from './Painel'
import { Carregando } from './ui'

function ate(resposta: Resposta): string {
  const dia = resposta.ate === resposta.hoje ? 'hoje' : formatarDiaMes(resposta.ate)
  if (resposta.modo === 'fatura') return `até ${dia}, véspera do pagamento da fatura`
  if (resposta.modo === 'fim-do-mes') return `até ${dia}, fim do mês`
  return `até ${dia}`
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

// A pergunta que faz o app ser aberto todo dia (ADR-048, ADR-063): o menor saldo projetado até o fim da
// janela que o usuário escolhe na engrenagem. O benefício fica à parte porque só paga as categorias dele.
export default function PossoGastar({ versao, aoSessaoExpirar }: { versao: number; aoSessaoExpirar: () => void }) {
  const [estado, setEstado] = useState<Estado<Resposta>>({ tipo: 'carregando' })
  const [configurando, setConfigurando] = useState(false)
  const [recarga, setRecarga] = useState(0)

  useEffect(() => {
    let vale = true
    carregar(fetchPossoGastar, 'quanto dá para gastar', aoSessaoExpirar, setEstado, () => vale)
    return () => {
      vale = false
    }
  }, [versao, recarga, aoSessaoExpirar])

  return (
    <section className="cartao posso-gastar" aria-label="Posso gastar">
      <header className="posso-topo">
        <h3 className="titulo-cartao">Posso gastar</h3>
        <button
          type="button"
          className="icone-botao"
          aria-label="Configurar o Posso gastar"
          disabled={estado.tipo !== 'ok'}
          onClick={() => setConfigurando(true)}
        >
          <IconeEngrenagem />
        </button>
      </header>
      {estado.tipo === 'carregando' && <Carregando texto="Calculando quanto dá para gastar..." linhas={2} />}
      {estado.tipo === 'erro' && (
        <p role="alert" className="erro">
          Não foi possível calcular quanto dá para gastar.
        </p>
      )}
      {estado.tipo === 'ok' && <Resultado resposta={estado.dados} />}
      {configurando && estado.tipo === 'ok' && (
        <Painel titulo="Posso gastar" aoFechar={() => setConfigurando(false)}>
          <Configuracao
            atual={estado.dados}
            aoSalvar={() => {
              setConfigurando(false)
              setRecarga((n) => n + 1)
            }}
            aoSessaoExpirar={aoSessaoExpirar}
          />
        </Painel>
      )}
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
      {resposta.rendimentoCofrinhoCentavos > 0 && (
        <p className="posso-nota">
          Inclui {formatarCentavos(resposta.rendimentoCofrinhoCentavos)} de rendimento estimado do cofrinho.
        </p>
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

const DIAS = Array.from({ length: 31 }, (_, i) => i + 1)

// Escolher o dia só muda a janela da próxima leitura: nenhum lançamento é tocado (ADR-063).
function Configuracao({
  atual,
  aoSalvar,
  aoSessaoExpirar,
}: {
  atual: Resposta
  aoSalvar: () => void
  aoSessaoExpirar: () => void
}) {
  const [dia, setDia] = useState(atual.diaLimite === null ? '' : String(atual.diaLimite))
  const [beneficios, setBeneficios] = useState(atual.incluirBeneficios)
  const [terceiros, setTerceiros] = useState(atual.incluirTerceiros)
  const [enviando, setEnviando] = useState(false)
  const [mensagem, setMensagem] = useState<string | null>(null)

  async function salvar(evento: FormEvent<HTMLFormElement>): Promise<void> {
    evento.preventDefault()
    setEnviando(true)
    setMensagem(null)
    try {
      await putPossoGastarConfiguracao({
        diaLimite: dia === '' ? null : Number(dia),
        incluirBeneficios: beneficios,
        incluirTerceiros: terceiros,
      })
      aoSalvar()
    } catch (erro) {
      if (erro instanceof SessaoExpiradaError) {
        aoSessaoExpirar()
        return
      }
      setMensagem('Não foi possível salvar. Tente de novo.')
    } finally {
      setEnviando(false)
    }
  }

  return (
    <form onSubmit={(evento) => void salvar(evento)} aria-label="Configurar o Posso gastar">
      <div className="campo">
        <label htmlFor="posso-dia">Contar entradas e saídas até o dia</label>
        <select id="posso-dia" value={dia} onChange={(evento) => setDia(evento.target.value)}>
          <option value="">Ciclo da fatura</option>
          {DIAS.map((valor) => (
            <option key={valor} value={valor}>
              Dia {valor}
            </option>
          ))}
        </select>
        <small>
          Dia 31 em mês curto vale o último dia do mês. Mudar o dia não altera nenhum lançamento, só a janela do cálculo.
          Sem dia, vale o ciclo da fatura de vencimento mais tarde, ou o fim do mês sem cartão.
        </small>
      </div>
      <fieldset className="posso-chaves">
        <legend>O que entra na conta</legend>
        <label className="posso-chave">
          <input type="checkbox" checked={beneficios} onChange={(evento) => setBeneficios(evento.target.checked)} />
          Benefícios
        </label>
        <label className="posso-chave">
          <input type="checkbox" checked={terceiros} onChange={(evento) => setTerceiros(evento.target.checked)} />
          Terceiros
        </label>
        <small>
          Ligado, o saldo entra no cálculo. Desligado, vale zero. O benefício só paga as categorias dele, então ligar
          pode mostrar mais do que dá para gastar em qualquer compra.
        </small>
      </fieldset>
      {mensagem !== null && (
        <p role="alert" className="erro">
          {mensagem}
        </p>
      )}
      <button type="submit" className="primario" disabled={enviando}>
        Salvar
      </button>
    </form>
  )
}
