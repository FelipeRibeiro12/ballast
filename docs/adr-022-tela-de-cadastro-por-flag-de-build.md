# ADR-022: Tela de cadastro visível só por flag de build

**Status:** aceito
**Data:** 2026-10-19

## Contexto

O dono pediu a tela de cadastro. A API já tem `POST /auth/cadastro`, mas a
rota só existe com `REGISTRO_ABERTO=true`, e fechada responde igual a uma rota
inexistente para não revelar que existe. Abrir o cadastro antes do limite por
IP (F20.5) permite criar conta em massa e esgotar memória com argon2.

## Decisão

- O web mostra o link e a tela de cadastro só quando é construído com
  `VITE_REGISTRO_ABERTO=true`. Sem a flag, a tela não entra na navegação.
- A flag é só de interface. Quem decide se o cadastro existe continua sendo o
  `REGISTRO_ABERTO` da API.
- O 201 do cadastro não cria sessão. O web chama o login com as mesmas
  credenciais logo em seguida, para o usuário cair logado.

## Justificativa

O web não tem como perguntar à API se o cadastro está aberto sem que a API
revele isso, e um endpoint de "cadastro aberto?" desfaria o cuidado da F1. A
flag de build é pública, mas só diz o que o próprio PWA mostra.

## Alternativas descartadas

- **Mostrar sempre e tratar o 401/404 como "cadastro fechado"**: em produção
  exibiria uma tela que nunca funciona.
- **Cadastro criar a sessão no 201**: muda o contrato da API e duplica o
  caminho de criação de sessão que hoje só o login tem.

## Consequência

As duas flags precisam andar juntas. Com só a do web, a tela aparece e o envio
dá erro genérico; com só a da API, a rota existe e ninguém a vê pelo PWA.
