# Segurança — mínimos não negociáveis

Carrega em toda sessão. São as regras que mudam **como o código é escrito** —
o resto do checklist (infra, processo) está na skill `/seguranca`.

## Segredo

- Segredo nunca no código, nunca no commit, nunca em log, nunca em mensagem de
  erro. Vem de variável de ambiente ou de cofre.
- `.env.example` com chaves e valores vazios. `.env` real no `.gitignore`.
- Segredo já commitado: **rotacionar a credencial é o primeiro passo**. Remover
  o arquivo não resolve, o histórico guarda. Pare e avise.
- No frontend, tudo que vai para o bundle é público. Chave de verdade nunca
  passa por lá.

## Entrada

- Validar na borda, sempre, com schema ou DTO. Depois da borda o tipo vale.
- Rejeitar campo não declarado (`whitelist`), não ignorar.
- Validar tipo, faixa e tamanho. Campo de texto sem limite é vetor de abuso.
- **Nunca** concatenar entrada em SQL, comando de shell, caminho de arquivo ou
  HTML. Query parametrizada, sempre.
- Saída para HTML é escapada pelo template, não na mão.

## Senha e sessão

- Senha com hash de derivação lenta e salt (bcrypt, argon2, scrypt). Nunca
  SHA/MD5, nunca criptografia reversível.
- Senha, token e hash nunca aparecem em log.
- Token e sessão têm expiração. Sessão eterna é conta comprometida para sempre.
- Logout invalida do lado do servidor, não só apaga o cookie.
- Cookie de sessão: `HttpOnly`, `Secure`, `SameSite`.
- Comparação de token ou hash em tempo constante.

## Autorização

- **Toda rota que não é pública verifica permissão explicitamente.** Autorização
  por omissão é a falha mais comum que existe.
- Autenticação responde "quem é"; autorização responde "pode isso **neste**
  recurso". Checar só o papel deixa um usuário ler o dado do outro.
- Menor privilégio: cada credencial, token e papel recebe o mínimo necessário.
  Conta de aplicação não usa usuário admin do banco.
- Nunca confie em dado vindo do cliente para decidir permissão.

## Erro e log

- Erro para o cliente é genérico. Stack trace, query e caminho interno ficam no
  log do servidor.
- Log de segurança registra: login (sucesso e falha), mudança de permissão,
  mudança de senha, acesso a dado sensível. Com quem, o quê, quando, de onde.
- Log nunca guarda senha, token, cartão ou documento. Mascare.

## Dependência

- Não instale dependência nova sem me perguntar antes.
- Lockfile commitado. CI com `--frozen-lockfile` ou equivalente.

## Quando parar e avisar

Qualquer um destes: segredo no histórico, rota sem verificação de permissão,
senha com hash fraco, query concatenada, dado sensível em log. Não contorne, não
deixe para depois — fale na hora.
