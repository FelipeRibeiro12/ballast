// A conta que a carteira entregou não é a do endereço cadastrado.
export class EnderecoDiferenteError extends Error {}

// Cada carteira recusa de um jeito: EIP-1193 usa o código 4001, o viem embrulha em
// UserRejectedRequestError e as do Wallet Standard só mandam a mensagem. Olha a cadeia de causas.
export function ehRecusaDoUsuario(erro: unknown, profundidade = 0): boolean {
  if (typeof erro !== 'object' || erro === null || profundidade > 4) return false
  if ('code' in erro && erro.code === 4001) return true
  if ('name' in erro && erro.name === 'UserRejectedRequestError') return true
  if ('message' in erro && typeof erro.message === 'string' && /user (rejected|denied)|rejected the request/i.test(erro.message)) {
    return true
  }
  return 'cause' in erro && ehRecusaDoUsuario(erro.cause, profundidade + 1)
}
