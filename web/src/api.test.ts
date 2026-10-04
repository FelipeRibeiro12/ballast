import type { Healthz, LoginRequest, Usuario } from './api'
import type { paths } from './api/schema'

describe('tipos do contrato', () => {
  it('Healthz vem do schema gerado do OpenAPI', () => {
    type Corpo200 = paths['/healthz']['get']['responses']['200']['content']['application/json']
    expectTypeOf<Healthz>().toEqualTypeOf<Corpo200>()
    expectTypeOf<Healthz>().toEqualTypeOf<{ mensagem: string }>()
  })
})

describe('tipos do contrato de autenticação', () => {
  it('Usuario vem do schema gerado do OpenAPI', () => {
    type Corpo200 = paths['/auth/eu']['get']['responses']['200']['content']['application/json']
    expectTypeOf<Usuario>().toEqualTypeOf<Corpo200>()
    expectTypeOf<Usuario>().toEqualTypeOf<{ id: string; email: string; nome: string | null }>()
  })

  it('LoginRequest vem do schema gerado do OpenAPI', () => {
    type Corpo = paths['/auth/login']['post']['requestBody']['content']['application/json']
    expectTypeOf<LoginRequest>().toEqualTypeOf<Corpo>()
  })
})
