# ADR-049: Limite de requisições, teto de usuários e flood, na F20.5

**Status:** aceito
**Data:** 2026-10-23

## Contexto

A F20.5 tem de existir antes do primeiro deploy público:
- o login sem limite aloca 64 MiB de argon2 por tentativa (auditoria da F1, M3);
- o `/healthz` público consulta o banco e grava stack trace a cada falha;
- `tentativa_login` e `sessao` crescem sem limpeza.

O dono pediu também um teto de usuários cadastrados, simples de tirar depois,
para a produção caber no tier gratuito (ADR-044). E pediu que flood e DDoS não
derrubem o app.

O caminho em produção é visitante, Vercel, Caddy na VM e API. O Caddy só aceita
requisição com o segredo de origem e põe o `x-real-ip` do Vercel no
`X-Forwarded-For`. O Tomcat troca o `RemoteAddr` por esse IP (ADR-044).

## Decisão

### Camadas

1. **Vercel:** a mitigação de DDoS da plataforma vale em todos os planos, o
   Hobby incluso, e segura o volume de rede antes de chegar à VM.
2. **Caddy:** requisição sem o segredo leva 403 sem tocar na API.
3. **API:** limite por IP e por rota, teto somado de login e cadastro,
   semáforo no argon2 e `/healthz` com uma consulta por vez.

A API não segura um ataque de volume: isso é papel do Vercel. Ela garante que
o que passa não esgota memória, CPU, pool de conexões nem disco de log.

### Limite por IP

- Filtro `RateLimitFilter`, o primeiro de todos (`HIGHEST_PRECEDENCE`, antes
  do limite de corpo e do Spring Security). A requisição recusada não lê
  corpo, não consulta sessão e não toca no banco.
- Janela fixa em memória, em `FixedWindowLimiter`. Uma instância só da API
  (ADR-044), então não precisa de Redis nem de dependência nova. A virada da
  janela deixa passar até o dobro num instante, o que basta contra flood.
- Os limites:

  | Regra | Chave | Limite |
  |---|---|---|
  | geral | IP | 600 por minuto |
  | login (`POST /auth/login`) | IP | 10 por minuto |
  | cadastro (`POST /auth/cadastro`) | IP | 5 por hora |
  | auth | todos os IPs | 60 por minuto, login e cadastro somados |

- O teto somado só gasta depois que o limite do IP aceitou. Senão um IP já
  barrado esgotaria o teto de todo mundo.
- Um flood espalhado por muitos IPs passa pelo limite de cada um. O teto
  somado é o que segura o argon2 nesse caso.
- IPv6 conta pelo `/64`, que é o que um provedor entrega a um cliente.
- A tabela guarda no máximo 20 mil IPs, uns 2 MB. Cheia, ela apaga as janelas
  vencidas. Se continuar cheia, o IP novo leva 429, e quem já está na tabela
  segue atendido.
- A resposta é 429 `{"erro":"muitas requisições, tente mais tarde"}`, com
  `Retry-After` em segundos até a janela virar.
- Cada chave gera uma linha de log por janela, na primeira recusa.
- Os números ficam em `ballast.rate-limit.*`, com os padrões de produção. A
  suíte de testes sobe todos para um milhão, porque faz tudo do mesmo
  127.0.0.1. O `RateLimitTest` sobe contexto próprio com limites baixos.

### Semáforo no argon2

- No máximo dois hashes ao mesmo tempo, um por núcleo da VM. Isso dá no máximo
  128 MiB de argon2.
- Quem espera mais de 5 s leva 503 `serviço indisponível` com
  `Retry-After: 5`, sem stack trace no log.
- No login, a tentativa já foi contada antes do argon2, então o 503 conta como
  falha. É o mesmo fail-closed da F1.
- Com o teto somado de 60 por minuto, o semáforo só enche durante um ataque.

### Teto de usuários

- `CADASTRO_MAX_USUARIOS`. Vazio vale 20, e aceita de 1 a 1.000.000. Valor
  inválido derruba a subida.
- A conta é feita na transação do cadastro, depois de uma trava consultiva
  (`pg_advisory_xact_lock`). Sem a trava, cadastros simultâneos contariam a
  mesma última vaga.
- Cheio, o cadastro responde 403
  `cadastro fechado: limite de usuários atingido` e não grava nada. O web
  mostra "O cadastro está fechado: o limite de usuários foi atingido.".
- O seed também respeita o teto. O dono sobe o número se precisar.
- **Para tirar o teto**, apague:
  - `RegistrationCap` e `RegistrationFullException`;
  - a chamada `cap.checkRoom()` em `IdentityService.register`;
  - o `catch` no `RegistrationController`;
  - a chave `registration-max-users` do `application.yml`;
  - o `RegistrationCapTest`.

### `/healthz` sob flood

- Uma consulta por vez. Quem chega com a consulta em andamento leva 503 sem
  tocar no banco, então o health ocupa no máximo uma conexão do pool. O cron
  e o deploy chamam um de cada vez.
- O stack trace da falha vai para o log no máximo uma vez por minuto.
- Separar liveness de readiness fica para depois. Com a vaga única e o limite
  por IP, o flood já não toma o pool.

### Limpeza

- Job diário às 04:00 (America/Sao_Paulo). Ele apaga sessão vencida ou
  revogada e tentativa de login sem falha há um dia.
- Um dia é muito mais que o atraso máximo de 15 min. Passado esse prazo, o
  e-mail volta a ter as três tentativas livres.
- Desliga com `BALLAST_IDENTITY_CLEANUP_JOB_ENABLED=false`. Ele acorda o Neon
  uma vez por dia, o que não pesa na conta de CU-hora do ADR-044.

### Custo

- Nos três serviços, o plano gratuito não cobra por excesso. Quando a cota
  acaba, o serviço para até o mês seguinte. Por isso o risco de um ataque é
  ficar fora do ar, e não uma fatura.
- Isso só vale enquanto o dono não colocar cartão nem trocar de plano. No
  Oracle, a conta precisa continuar Always Free, sem upgrade para Pay As You
  Go.
- Um flood espalhado que acorde o Neon o tempo todo gasta as 100 CU-hora do
  mês. O limite por IP e o filtro de origem diminuem isso, mas não eliminam.
  Uma regra de firewall no Vercel fecharia essa porta (ver o runbook).

## Consequências

- `REGISTRO_ABERTO=true` passa a ser aceitável em produção, com o teto valendo.
  A trava no deploy pedida no PR #6 deixa de ser necessária.
- Num ataque ao login, o dono também pode levar 429 por até um minuto. As
  sessões abertas (7 dias) seguem valendo.
- O atraso por e-mail (M2 da F1) continua: um terceiro que conheça o e-mail
  mantém a vítima no atraso com uma tentativa por janela. O limite por IP não
  resolve isso. A dívida segue para a F20.
- Com duas instâncias da API, cada uma contaria a sua janela. Escalar
  horizontalmente pede estado compartilhado, com outro ADR.
- Ficam no backlog: rejeitar parâmetro de query desconhecido, a página de erro
  do Tomcat em `%2F`, os tetos de contas e recorrências por usuário e o
  liveness separado do readiness.
