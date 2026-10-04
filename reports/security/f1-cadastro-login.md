# Auditoria de segurança: F1 (cadastro, login, logout, /auth/eu, sessão, atraso de tentativa, seed)

Data: 2026-10-04. Branch `feat/f1-cadastro-login`, diff `main...HEAD`.
Escopo: o que está no repositório. Nada sobre produção foi verificado.

## Resumo

| Gravidade | Qtde |
|---|---|
| Crítico | 0 |
| Alto | 0 |
| Médio | 3 |
| Baixo | 4 |

Nenhum segredo no código, no histórico ou nos logs. A espinha da F1 (argon2id,
token de 256 bits com só o SHA-256 no banco, revogação no servidor, guarda de
COOKIE_SECURE, SQL parametrizado, `usuario_id` só da sessão) está correta. Os
achados são todos sobre abuso do login por quem não tem conta, e os três
Médios têm o mesmo remédio de fundo: limite por IP/concorrência na borda
(F20.5), que **não existe no repositório**.

## Médio

### M1. Corrida no atraso: o limite de tentativas vale por lote, não por tentativa
- **Onde:** `api/internal/identidade/servico.go:96-121` (`Autentica`), com `esperaRestante` (123-138) e `registraFalha` (142-149).
- **O que:** a checagem do atraso (leitura) e o registro da falha (UPSERT) são passos separados, com o argon2 (centenas de ms) no meio. O incremento é atômico, a decisão de deixar passar não é.
- **Como é explorado:** o atacante manda N requisições de login para o mesmo e-mail em paralelo (por exemplo 200 conexões). Todas leem `falhas <= 3` ou o atraso já vencido, todas passam pela checagem e todas verificam a senha. O contador só sobe depois. Passado o atraso (até 15 min), repete o lote.
- **Impacto:** o teto efetivo vira N chutes por janela de atraso, em vez de 4 + 1 por janela. Enfraquece a defesa contra força bruta de senha da conta. Limitado pela memória do argon2 (ver M3) e pelo tamanho mínimo de 12 caracteres da senha.
- **Correção (backend):** decidir e registrar no mesmo comando atômico: um `UPDATE ... RETURNING` (ou `SELECT ... FOR UPDATE` numa transação) que reserva a tentativa antes de verificar a senha e a devolve no sucesso. Teste com tentativas concorrentes.

### M2. O 429 por e-mail permite trancar o dono para fora indefinidamente
- **Onde:** `servico.go:70-88` (atraso), `servico.go:97-99` e `auth.go:213-220` (429).
- **O que:** a chave do atraso é só o e-mail digitado. Quem conhece o e-mail da vítima provoca falhas e a conta entra em atraso de até 15 min, sem precisar de nenhum credencial.
- **Como é explorado:** o atacante envia 4 logins errados para `vitima@dominio`. Quando o atraso acaba, 1 tentativa errada a cada 15 min mantém o contador no teto. Uma requisição a cada 15 min, de qualquer IP. Os 429 não revelam se a conta existe, mas o dono real recebe 429 em toda tentativa. Sessões já abertas continuam valendo.
- **Impacto:** negação de serviço de login contra um usuário específico. Num app de usuário único, é o app inteiro indisponível para o dono. Não há caminho de recuperação a não ser mexer no banco.
- **Correção:** é decisão de produto (o `docs/ameacas.md` pede limite por conta). Opções: atraso por par (e-mail, IP) somado a um teto por conta mais alto; ou deixar o dono entrar com um segundo fator ou link por e-mail quando houver (F20). Registrar a decisão em ADR. Faixa: backend. Mitigação parcial: o rate limit por IP na borda reduz o ritmo, mas não elimina.

### M3. Login sem limite de concorrência: DoS de memória pelo argon2, e `tentativa_login` cresce sem limite
- **Onde:** `servico.go:97-114` (argon2 de 64 MiB por tentativa, inclusive para e-mail inexistente via `VerificaSenhaFicticia`, `senha.go:94-104`); `migrations/00002_...sql:30-36` e `servico.go:142-149` (uma linha por e-mail digitado, nunca removida sem sucesso).
- **O que:** nenhum limite de requisições simultâneas ou por IP no código. Cada login que passa na validação de formato aloca 64 MiB e consome CPU. E cada e-mail distinto com falha cria uma linha permanente em `tentativa_login`.
- **Como é explorado:** sem autenticação, o atacante abre centenas de conexões com e-mails aleatórios válidos. 100 logins simultâneos pedem ~6,4 GiB. Numa instância pequena, o processo morre por OOM ou o servidor entra em swap. Em paralelo, cada e-mail aleatório deixa uma linha (até ~254 caracteres) na tabela, que só cresce.
- **Impacto:** indisponibilidade remota sem autenticação da API inteira (mesmo processo). Crescimento do banco como efeito colateral, lento.
- **Correção:** (backend) semáforo limitando argon2 simultâneos (retorna 503/429 acima do limite); job de limpeza de `tentativa_login` com `ultima_falha_em` antigo, como parte da F20.5. (devops) rate limit por IP na borda. O comentário em `.env.example` já condiciona `REGISTRO_ABERTO` ao F20.5, mas o login já está exposto hoje, então o limite precisa existir antes do primeiro deploy público, não só antes de abrir o cadastro.

## Baixo

### B1. `POST /auth/cadastro` aberto enumera e-mails (409)
- **Onde:** `api/internal/http/auth.go:89-92`.
- **Como é explorado:** com `REGISTRO_ABERTO=true`, enviar um e-mail e ler 409 (existe) ou 201 (não existia, e agora existe). Sem rate limit, enumera uma lista.
- **Por que é Baixo:** a rota está fechada por padrão (404 sem `Allow`, conferido em `router.go:15-17`) e o 409 é inerente a cadastro sem confirmação por e-mail. Registrar como risco aceito ligado ao F20.5. Faixa: backend, se for corrigir (resposta uniforme com confirmação por e-mail).

### B2. `.env.example` entrega o par que desliga a guarda do cookie
- **Onde:** `.env.example:11-12` (`COOKIE_SECURE=false` e `APP_ENV=development`).
- **O que:** a guarda (`cmd/api/config.go`) só protege contra `COOKIE_SECURE=false` sozinho. Quem copia o `.env.example` para produção sem editar sobe com cookie sem `Secure` e a guarda satisfeita.
- **Como é explorado:** exige erro de configuração de quem faz o deploy. Em rede sem TLS de ponta a ponta, o cookie de sessão trafega em HTTP.
- **Correção (devops/backend):** deixar `COOKIE_SECURE` e `APP_ENV` comentados no exemplo, ou `APP_ENV=production` e uma linha dizendo o que mudar para desenvolvimento. Não verificável a partir do repositório: o valor real em produção.

### B3. IP do log e do futuro rate limit é o `RemoteAddr`
- **Onde:** `auth.go:173-181`.
- **O que:** atrás de proxy, todos os registros de login ficam com o IP do proxy, e qualquer limite por IP vale para todos juntos. O código já comenta isso. Registrado para não ser esquecido no deploy: decidir `X-Forwarded-For` só com proxy confiável. Faixa: devops/backend.

### B4. Endurecimento sem exploração direta
- Sessões expiradas ou revogadas nunca são apagadas (`sessao` cresce a cada login) e não há limite de sessões ativas por usuário. Só afeta quem já tem credencial.
- Cookie sem prefixo `__Host-` (`auth.go:188`): impede que um subdomínio irmão plante cookie com o mesmo nome. Relevante só se houver outros subdomínios.
- A API não manda `X-Content-Type-Options: nosniff` nem cabeçalhos do tipo; as respostas são JSON com `Content-Type` correto, então o ganho é pequeno. O servidor do PWA (CSP, HSTS) é de infra e não está no repositório.

## Conferido, sem achado

Só o que mudaria o julgamento de quem lê: argon2id com salt de 16 bytes, m=64 MiB, t=3, p=2, comparação com `subtle.ConstantTimeCompare`, teto de 128 caracteres antes do hash; token de 32 bytes de `crypto/rand`, só o SHA-256 no banco, expiração absoluta de 7 dias, revogação por UPDATE no servidor, token novo a cada login (não há fixação); cookie HttpOnly, Secure por padrão, SameSite=Lax; CSRF sem brecha: login e cadastro exigem `application/json` (formulário cross-site não consegue enviar, e não há CORS), `GET /auth/eu` só lê, e o logout sem checagem de Content-Type não recebe o cookie em POST cross-site por causa do Lax, com impacto nulo de qualquer forma; sem enumeração por mensagem ou por tempo no login (mesma mensagem, argon2 fictício, e o 429 vale também para e-mail inexistente); `exigeSessao` é a única origem do `usuario_id`, nenhuma query da F1 usa identificador do cliente; todo SQL é parametrizado; corpo limitado a 8 KiB, campos desconhecidos rejeitados, controle rejeitado; erros genéricos ao cliente, e-mail mascarado, nenhum log com senha ou token; seed lê o ambiente e não imprime senha, e-mail nem URL; timeouts do servidor definidos; frontend sem token no JS (cookie HttpOnly, nada em localStorage), textos do servidor nunca renderizados (mensagens são fixas no cliente), React escapa `nome` e `email`.
Senhas de teste e do histórico são fictícias (`senha-de-teste-12345` e semelhantes); `git log -p` de `.env.example` só mostra chaves vazias; `.env` e `.env.*` estão no `.gitignore` e nenhum `.env` real está versionado. `docker-compose.yml` publica o Postgres só em `127.0.0.1`.
Dependências: `pnpm audit --prod` sem vulnerabilidades conhecidas. `golang.org/x/crypto`, `google/uuid` e `kin-openapi` são pacotes esperados, sem sinal de typosquatting.

## O que esta auditoria NÃO cobriu

- **Produção:** HTTPS/TLS ativo, valor real de `COOKIE_SECURE`/`APP_ENV`/`REGISTRO_ABERTO`, rate limit ou WAF na borda, proxy e cabeçalho de IP, backup, alertas. Quem confirma: devops.
- **`govulncheck` não está instalado** neste ambiente e não foi rodado; as dependências Go não têm varredura de vulnerabilidade nesta auditoria. Rodar no CI (devops).
- Dívida já registrada e não reavaliada: caracteres Cf/bidi em e-mail e nome.
- Não houve teste de intrusão nem execução contra banco ou servidor: tudo é leitura de código. O comportamento concorrente de M1 e M3 foi deduzido do código, não medido.
- Frontend: não foi auditado o bundle gerado nem o servidor de estáticos.
- Fatias fora da F1 (lançamentos, cripto, importação) e a recuperação de senha, que não existe ainda.

## Recomendação

Vai com ressalva registrada. Nenhum crítico ou alto. M1 deve ser corrigido antes de o login ficar exposto à internet. M2 precisa de decisão de produto registrada. M3 e B3 dependem de rate limit na borda (F20.5), que tem de existir antes do primeiro deploy público, não só antes de abrir o cadastro. Quem decide é o tech-lead.
