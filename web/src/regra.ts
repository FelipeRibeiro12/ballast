import type { RegraRecorrencia } from './api'
import { formatarDataCompleta, formatarDiaMes } from './datas'

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

function detalheDaFrequencia(regra: RegraRecorrencia): string {
  if (regra.frequencia === 'semanal' && regra.diaDaSemana !== null) {
    return ` ${DIAS_DA_SEMANA[regra.diaDaSemana] ?? ''}`.trimEnd()
  }
  if (regra.frequencia === 'mensal' && regra.diaDoMes !== null) return ` no dia ${regra.diaDoMes}`
  // Anual repete dia e mês do início; formatarDiaMes devolve dd/mm.
  if (regra.frequencia === 'anual') return ` em ${formatarDiaMes(regra.inicio)}`
  return ''
}

export function descreverRegra(regra: RegraRecorrencia): string {
  const unidade = UNIDADES[regra.frequencia]
  const base = regra.intervalo === 1 ? unidade.todo : `A cada ${regra.intervalo} ${unidade.plural}`
  let texto = base + detalheDaFrequencia(regra)
  if (regra.fim !== null) texto += `, até ${formatarDataCompleta(regra.fim)}`
  else if (regra.ocorrenciasMax !== null) {
    texto += `, ${regra.ocorrenciasMax} ${regra.ocorrenciasMax === 1 ? 'vez' : 'vezes'}`
  }
  return texto
}
