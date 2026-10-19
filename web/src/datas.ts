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
export function formatarDiaMes(data: string): string {
  return `${data.slice(8, 10)}/${data.slice(5, 7)}`
}

const formatadorDoMes = new Intl.DateTimeFormat('pt-BR', { month: 'long', year: 'numeric' })

export function rotuloDoMes({ ano, mes }: Mes): string {
  return formatadorDoMes.format(new Date(ano, mes - 1, 1))
}
