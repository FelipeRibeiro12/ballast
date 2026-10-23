// Datas do navegador em AAAA-MM-DD. Nunca usa toISOString: ele converte para UTC
// e, perto da meia-noite no Brasil, devolve o dia seguinte.

export type Mes = { ano: number; mes: number }

function doisDigitos(n: number): string {
  return String(n).padStart(2, '0')
}

export function formatarData(ano: number, mes: number, dia: number): string {
  return `${String(ano).padStart(4, '0')}-${doisDigitos(mes)}-${doisDigitos(dia)}`
}

// Argumento só existe para o teste fixar o relógio.
export function hojeLocal(agora: Date = new Date()): string {
  return formatarData(agora.getFullYear(), agora.getMonth() + 1, agora.getDate())
}

// O mês da API, AAAA-MM.
export function mesIso({ ano, mes }: Mes): string {
  return `${String(ano).padStart(4, '0')}-${doisDigitos(mes)}`
}

export function lerMesIso(texto: string): Mes {
  const [ano, mes] = texto.split('-').map(Number)
  return { ano, mes }
}

export function mesDeHoje(agora: Date = new Date()): Mes {
  return { ano: agora.getFullYear(), mes: agora.getMonth() + 1 }
}

function eBissexto(ano: number): boolean {
  return (ano % 4 === 0 && ano % 100 !== 0) || ano % 400 === 0
}

export function ultimoDiaDoMes({ ano, mes }: Mes): number {
  if (mes === 2) return eBissexto(ano) ? 29 : 28
  return [4, 6, 9, 11].includes(mes) ? 30 : 31
}

export function intervaloDoMes(alvo: Mes): { de: string; ate: string } {
  return {
    de: formatarData(alvo.ano, alvo.mes, 1),
    ate: formatarData(alvo.ano, alvo.mes, ultimoDiaDoMes(alvo)),
  }
}

export function somarMeses({ ano, mes }: Mes, delta: number): Mes {
  const indice = ano * 12 + (mes - 1) + delta
  return { ano: Math.floor(indice / 12), mes: (indice % 12) + 1 }
}

// "2026-09-05" vira "05/09". Trabalha na string: Date interpretaria como UTC.
// O dia de um instante em São Paulo, como "2026-10-21". O en-CA formata a data em AAAA-MM-DD.
const formatadorDiaSaoPaulo = new Intl.DateTimeFormat('en-CA', {
  timeZone: 'America/Sao_Paulo',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
})

export function diaEmSaoPaulo(instante: string): string {
  return formatadorDiaSaoPaulo.format(new Date(instante))
}

const formatadorHoraSaoPaulo = new Intl.DateTimeFormat('pt-BR', {
  timeZone: 'America/Sao_Paulo',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
})

// A hora de um instante em São Paulo, como "22:30".
export function horaEmSaoPaulo(instante: string): string {
  return formatadorHoraSaoPaulo.format(new Date(instante))
}

export function formatarDiaMes(data: string): string {
  return `${data.slice(8, 10)}/${data.slice(5, 7)}`
}

// "2026-09-05" vira "05/09/2026", também sem passar por Date.
export function formatarDataCompleta(data: string): string {
  return `${formatarDiaMes(data)}/${data.slice(0, 4)}`
}

const formatadorDoMes = new Intl.DateTimeFormat('pt-BR', { month: 'long', year: 'numeric' })

export function rotuloDoMes({ ano, mes }: Mes): string {
  return formatadorDoMes.format(new Date(ano, mes - 1, 1))
}

// Soma dias no calendário local. Date resolve virada de mês e ano bissexto; a conta
// nunca passa por UTC, então a hora de verão não desloca o dia.
export function somarDias(data: string, dias: number): string {
  const base = new Date(Number(data.slice(0, 4)), Number(data.slice(5, 7)) - 1, Number(data.slice(8, 10)) + dias)
  return formatarData(base.getFullYear(), base.getMonth() + 1, base.getDate())
}

const formatadorDaSemana = new Intl.DateTimeFormat('pt-BR', { weekday: 'long' })

// Título do grupo de um dia na lista: "Hoje, 19/10", "Ontem, 18/10" ou "Segunda-feira, 05/10".
export function rotuloDoDia(data: string, hoje: string): string {
  const dataCurta = formatarDiaMes(data)
  if (data === hoje) return `Hoje, ${dataCurta}`
  if (data === somarDias(hoje, -1)) return `Ontem, ${dataCurta}`
  if (data === somarDias(hoje, 1)) return `Amanhã, ${dataCurta}`
  const diaDaSemana = formatadorDaSemana.format(
    new Date(Number(data.slice(0, 4)), Number(data.slice(5, 7)) - 1, Number(data.slice(8, 10))),
  )
  return `${diaDaSemana.charAt(0).toUpperCase()}${diaDaSemana.slice(1)}, ${dataCurta}`
}
