import type { Recorrencia, RegraRecorrencia } from './api'
import { formatarDataCompleta, formatarDiaMes } from './datas'
import { formatarCentavos } from './dinheiro'
import { formatarPercentual } from './percentual'

type Divisao = Recorrencia['divisao']

// Com a preposição junto: "no" para domingo e sábado, "na" para os dias com "-feira".
const DIAS_DA_SEMANA = [
  'no domingo',
  'na segunda-feira',
  'na terça-feira',
  'na quarta-feira',
  'na quinta-feira',
  'na sexta-feira',
  'no sábado',
]

const UNIDADES = {
  diaria: { singular: 'dia', plural: 'dias', todo: 'Todo dia' },
  semanal: { singular: 'semana', plural: 'semanas', todo: 'Toda semana' },
  mensal: { singular: 'mês', plural: 'meses', todo: 'Todo mês' },
  anual: { singular: 'ano', plural: 'anos', todo: 'Todo ano' },
} as const

// "15", "15 e 30", "5, 20 e 30".
function juntarDias(dias: number[]): string {
  if (dias.length === 1) return String(dias[0])
  return `${dias.slice(0, -1).join(', ')} e ${dias[dias.length - 1]}`
}

function detalheDaFrequencia(regra: RegraRecorrencia, divisao: Divisao): string {
  if (divisao !== null) {
    // O diaDoMes da regra é só o dia da parcela 1 (ADR-027); quem manda são as parcelas.
    const dias = divisao.parcelas.map((parcela) => parcela.diaDoMes).sort((a, b) => a - b)
    return ` nos dias ${juntarDias(dias)}`
  }
  if (regra.frequencia === 'semanal' && regra.diaDaSemana !== null) {
    return ` ${DIAS_DA_SEMANA[regra.diaDaSemana] ?? ''}`.trimEnd()
  }
  if (regra.frequencia === 'mensal' && regra.diaDoMes !== null) return ` no dia ${regra.diaDoMes}`
  // Anual repete dia e mês do início; formatarDiaMes devolve dd/mm.
  if (regra.frequencia === 'anual') return ` em ${formatarDiaMes(regra.inicio)}`
  return ''
}

export function descreverRegra(regra: RegraRecorrencia, divisao: Divisao = null): string {
  const unidade = UNIDADES[regra.frequencia]
  const base = regra.intervalo === 1 ? unidade.todo : `A cada ${regra.intervalo} ${unidade.plural}`
  let texto = base + detalheDaFrequencia(regra, divisao)
  if (regra.fim !== null) texto += `, até ${formatarDataCompleta(regra.fim)}`
  else if (regra.ocorrenciasMax !== null) {
    texto += `, ${regra.ocorrenciasMax} ${regra.ocorrenciasMax === 1 ? 'vez' : 'vezes'}`
  }
  return texto
}

// "dia 15: R$ 2.000,00 (40%)"; no modo valor não há percentual a mostrar.
export function descreverParcelas(divisao: NonNullable<Divisao>): string[] {
  return divisao.parcelas.map((parcela) => {
    const valor = formatarCentavos(parcela.valorParcelaCentavos)
    const percentual =
      parcela.percentualCentesimos === undefined ? '' : ` (${formatarPercentual(parcela.percentualCentesimos)})`
    return `dia ${parcela.diaDoMes}: ${valor}${percentual}`
  })
}
