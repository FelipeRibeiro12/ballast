# ADR-053: MFA endurecido e atraso do login por IP

**Status:** aceito
**Data:** 2026-10-24

## Contexto

O ADR-050 deixou quatro pontos abertos, três deles registrados como dívida:
- ligar o MFA não derrubava as sessões que já estavam abertas;
- os códigos de recuperação só se renovavam desligando e ligando o MFA;
- apagar conta não pedia o código, embora `docs/seguranca.md` (item 8) peça
  segundo fator em ação sensível;
- o atraso do login por e-mail (M2 da F1, ADR-049) deixa um terceiro, que só
  conhece o e-mail, manter a vítima sob atraso com uma tentativa por janela.

A decisão 4 é **do dono**, tomada em 2026-10-24. As outras três seguem o que o
ADR-050 e a seguranca.md já pediam.

## Decisão

### 1. Ativar o MFA revoga as outras sessões

- `POST /auth/mfa/ativar` marca como revogadas todas as sessões ativas do
  usuário, menos a que fez a chamada.
- A revogação roda na mesma transação que liga o MFA: ou liga e derruba as
  outras, ou nada muda. Código errado não revoga nada.
- Quem roubou a sessão antes do MFA não fica dentro depois dele. A sessão que
  ativou continua, porque o dono está nela.
- O log registra quantas sessões caíram, sem token.

### 2. Códigos de recuperação novos

- `POST /auth/mfa/codigos-de-recuperacao` com `{"codigo"}` gera dez códigos
  novos e invalida todos os antigos, os usados e os não usados.
- O código é o **do app de agora**. Código de recuperação não vale: quem tem só
  um código de recuperação, ou só a sessão, não renova a lista. O passo do
  código também não pode ter sido usado (ADR-050).
- Gastar o código do app e trocar a lista são uma transação só.
- Usa o mesmo atraso por usuário do MFA (3 falhas livres, depois 2^(n−3) s, até
  15 min) e conta no limite de login por IP do ADR-049, como `/ativar`.
- A resposta é a mesma do `/ativar`: `{"codigosDeRecuperacao"}`, dez códigos
  `xxxxx-xxxxx`, só nessa resposta. O log de segurança registra a renovação com
  usuário e IP, sem códigos.
- Com o MFA desligado, 409 `mfa não está ativo`.

### 3. Apagar conta pede o código

"Conta" aqui é a conta financeira (`DELETE /contas/{id}`, ADR-036).

- **O código vai num corpo JSON opcional no próprio `DELETE`**: `{"codigo"}`,
  do app ou de recuperação, de 1 a 32 caracteres.
- Sem MFA, o corpo é ignorado e o `DELETE` sem corpo segue valendo: o
  comportamento atual não muda.
- Com MFA, corpo ausente ou código errado dá 400 `código inválido`, e nada é
  apagado. No atraso do MFA, 429 com `Retry-After`.
- O código é conferido **antes** de olhar a conta. Assim a resposta sobre o
  código é igual para conta própria, de outro usuário e inexistente, e o 404
  não vira pista. O custo: um código certo gasta mesmo quando a regra do
  `DELETE` responde 400 ou 404 depois.
- A conferência de `Origin` do ADR-051 continua valendo: o filtro roda antes
  de tudo e vale para todo método que muda estado, com ou sem corpo.
- Por que corpo no `DELETE` e não uma rota `POST` de confirmação:
  - não cria rota nem operação nova, e o web que já chama o `DELETE` só
    acrescenta o corpo;
  - o corpo de `DELETE` é aceito por `fetch`, pelo Vercel e pelo Caddy, e o
    limite de 4 KiB do `BodyLimitFilter` vale para ele;
  - o custo é que alguns proxies e clientes antigos descartam corpo de
    `DELETE`. Nesse caso o MFA ligado responderia 400 em vez de apagar, o que
    falha fechado. Se aparecer, a troca é para `POST /contas/{id}/apagar`, com
    novo ADR.
- A verificação mora em `MfaGate`, a porta que outras fatias usam para pedir o
  código em ação sensível (troca de e-mail, quando existir). O pacote `accounts`
  não conhece o MFA, só pede o código.
- O tratamento de `MfaError` saiu do `MfaController` para o
  `ApiExceptionHandler`, porque agora vale fora de `/auth`.

### 4. Atraso do login por e-mail e IP (decisão do dono)

- A chave de `tentativa_login` passa a ser `(email, ip)` (migration V21).
  O atraso por usuário do MFA fica como está.
- O IP é o `RemoteAddr`, o mesmo do limite por IP (ADR-049). Em produção o
  Tomcat só troca esse endereço pelo `X-Forwarded-For` que o Caddy manda, e só
  confia em proxy interno (ADR-044). **Cabeçalho do cliente não escolhe a
  chave**, e há teste com `X-Forwarded-For` e `X-Real-IP` forjados.
- IPv6 conta pelo `/64` (`ClientKey`, a mesma função do limite por IP). Quem
  troca de endereço dentro do próprio bloco não ganha tentativas novas.
- Sem enumeração de conta: a chave inclui o e-mail digitado, exista conta ou
  não, e o caminho é o mesmo. E-mail inexistente se comporta como o existente
  em cada IP, com a mesma resposta e o mesmo `Retry-After`.
- Login certo apaga só a linha do próprio IP. A linha de um IP que errou a
  senha dessa conta fica, e o dono não destrava o atraso de outro.
- O terceiro de outro IP não mantém a vítima sob atraso: ele gasta o atraso
  dele, e a vítima segue com as três tentativas livres do endereço dela.

## Consequências

- **O atraso por e-mail deixa de proteger sozinho contra quem troca de IP.**
  Cada IP tem 3 palpites livres por e-mail. O que segura um ataque distribuído
  é o resto: o limite de 10 logins por minuto por IP e o teto somado de 60 por
  minuto de todos os IPs (ADR-049), e o argon2 limitado a duas vezes ao mesmo
  tempo. Contas com MFA ligado não abrem sessão só com a senha, e o atraso do
  MFA é por usuário. Sem MFA, a senha longa e única continua sendo a defesa.
- A tabela `tentativa_login` pode ter várias linhas por e-mail. O volume é
  limitado pelo limite por IP e pela limpeza diária (ADR-049), que não mudou.
- O web pode mostrar o MFA em ações sensíveis: `GET /auth/mfa` diz se o MFA
  está ligado, e então o `DELETE /contas/{id}` pede o código.
- Ligar o MFA derruba a sessão de outros aparelhos do mesmo usuário. O web deve
  avisar.
- Linhas antigas de `tentativa_login` ficam com `ip` vazio e a limpeza diária
  apaga. Para quem estava sob atraso na hora da migração, o atraso zera.
