import type { Healthz } from './api'
import type { paths } from './api/schema'

describe('tipos do contrato', () => {
  it('Healthz vem do schema gerado do OpenAPI', () => {
    type Corpo200 = paths['/healthz']['get']['responses']['200']['content']['application/json']
    expectTypeOf<Healthz>().toEqualTypeOf<Corpo200>()
    expectTypeOf<Healthz>().toEqualTypeOf<{ mensagem: string }>()
  })
})
