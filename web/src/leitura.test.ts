import { ApiError, LimiteTentativasError } from './api'
import { carregar, type Estado } from './leitura'

function carregarComErro(erro: unknown): Promise<Estado<string>> {
  return new Promise((resolver) => {
    carregar(
      () => Promise.reject(erro),
      'as contas',
      () => undefined,
      resolver,
      () => true,
    )
  })
}

describe('carregar', () => {
  it('429 pede para esperar um instante e tentar de novo', async () => {
    const estado = await carregarComErro(new LimiteTentativasError(30))
    expect(estado).toEqual({
      tipo: 'erro',
      mensagem: 'Muitas requisições em pouco tempo. Espere um instante e tente de novo.',
    })
  })

  it('outro erro continua com a mensagem genérica', async () => {
    const estado = await carregarComErro(new ApiError('x respondeu 500'))
    expect(estado).toEqual({ tipo: 'erro', mensagem: 'Não foi possível carregar as contas.' })
  })
})
