# Segurança — categoria código

Verificável lendo o repositório. Dono: `backend`, `frontend`, `dba`.

## Validação de entrada

Toda rota pública tem schema ou DTO validando entrada. Confira:

- O validador está **ligado** — no Nest, `ValidationPipe` global com
  `whitelist: true`. DTO sem o pipe é só documentação.
- Campo não declarado é **rejeitado**, não ignorado.
- Tipo, faixa e tamanho máximo. Campo de texto sem limite é vetor de abuso de
  memória e de armazenamento.
- Entrada numérica validada como número, não convertida silenciosamente.

Como achar o que falta: liste as rotas e cruze com os DTOs.

```bash
grep -rn "@Get\|@Post\|@Put\|@Patch\|@Delete" src/ | wc -l
grep -rln "class.*Dto" src/ | wc -l
```

Diferença grande entre os dois números é onde olhar.

## Sanitização e saída

- **Escape é na saída, não na entrada.** O mesmo texto é seguro em JSON e
  perigoso em HTML. Guarde cru, escape ao renderizar.
- Em React, `dangerouslySetInnerHTML` só com HTML passado por sanitizador.
- Markdown de usuário renderizado precisa de sanitizador, sempre.
- Nome de arquivo de upload nunca vai direto para o caminho do disco:
  `../../etc/passwd` é um nome de arquivo válido.

## SQL injection

- Query **sempre** parametrizada. Procure concatenação e template string:

```bash
grep -rnE '(query|execute|raw)\s*\(\s*[`"'"'"'].*\$\{' src/
grep -rn "queryRaw\|\.raw(\|Sprintf.*SELECT" src/
```

- ORM protege no caminho normal e **não** protege no `raw`. Todo `raw` é ponto
  de inspeção.
- `ORDER BY` com coluna vinda do cliente não aceita parâmetro: valide contra
  uma lista fechada de colunas permitidas.
- O mesmo raciocínio vale para comando de shell e para caminho de arquivo.

## Senha

- Hash de derivação lenta com salt: **argon2id** (preferido), **bcrypt** ou
  **scrypt**. Nunca SHA, nunca MD5, nunca criptografia reversível.
- Custo configurado, não o padrão mínimo da biblioteca.
- Comparação pela função `verify` da própria biblioteca, nunca `==`.
- Mínimo de 8 a 12 caracteres e verificação contra lista de senha vazada vale
  mais que exigir símbolo e maiúscula.
- Mensagem de login errado é **a mesma** para usuário inexistente e senha
  errada. Mensagem diferente entrega quais contas existem.

## Sessão e expiração

- Token de acesso curto (minutos a poucas horas) + refresh token longo, ou
  sessão no servidor com expiração.
- **Logout invalida no servidor.** Apagar o cookie não invalida um JWT — ele
  continua válido até expirar. Se precisa de logout real, precisa de lista de
  revogação ou sessão com estado.
- Expiração por inatividade **e** prazo máximo absoluto.
- Sessão renovada (novo identificador) após o login: evita fixação de sessão.
- Mudança de senha invalida as outras sessões.
- Cookie: `HttpOnly`, `Secure`, `SameSite=Lax` ou `Strict`.
- Comparação de token em tempo constante.

## MFA

- Segundo fator no login e antes de ação sensível (trocar senha, trocar e-mail,
  tirar dinheiro).
- TOTP é o padrão razoável. SMS é o mais fraco de todos — serve se a
  alternativa for nada.
- O segredo do TOTP é guardado **cifrado**, não em texto.
- Código de recuperação gerado uma vez, mostrado uma vez, guardado em hash
  como se fosse senha.
- **Limite de tentativa no segundo fator.** Sem isso, 6 dígitos caem por força
  bruta.
- Verifique o que acontece se o usuário perder o fator: se não há caminho, o
  suporte vai virar o bypass.

## Controle de acesso

O item que mais falha, e o mais caro de consertar depois.

- **Toda rota não pública verifica permissão explicitamente.** Autorização por
  omissão é a falha número um.
- Autenticação diz "quem é"; autorização diz "pode isso **neste recurso**".
  Checar só o papel deixa `GET /pedidos/123` devolver o pedido de qualquer um.
  Teste isso: logue como usuário A e peça o recurso do B.
- Permissão decidida no servidor, nunca com dado enviado pelo cliente. Nenhum
  `role` vindo do corpo da requisição.
- Esconder o botão na interface não é controle de acesso.
- Identificador sequencial exposto facilita varredura. Não é uma falha por si,
  mas eleva o impacto de qualquer furo de autorização.

## Menor privilégio no código

- A aplicação conecta no banco com usuário que tem só o que precisa — não com
  o dono do schema, nunca com superusuário.
- Chave de API de terceiro com o escopo mínimo.
- Usuário novo nasce sem permissão e ganha o que precisa, não o contrário.
- Token de CI com o escopo mínimo e prazo curto.

## Criptografia

- **Não escreva algoritmo.** Use a biblioteca padrão da plataforma.
- Em trânsito: TLS, verificação de certificado ligada. Desligar verificação
  "para funcionar em dev" vaza para produção.
- Em repouso: cifre o que é sensível de verdade (documento, dado de saúde,
  token de terceiro). Cifrar tudo sem critério só atrapalha consulta.
- AES-GCM ou equivalente autenticado. Nunca ECB. Nunca reutilizar nonce.
- Chave de criptografia é segredo: vem do cofre, com rotação prevista.
- Hash de senha é derivação, não criptografia — são coisas diferentes.

## Log de segurança

- Registre: login (sucesso **e** falha), logout, mudança de senha, mudança de
  permissão, acesso a dado sensível, falha de autorização.
- Cada evento com: quem, o quê, quando, de onde (IP), resultado.
- **Nunca** registre senha, token, cartão, documento, dado de saúde. Mascare.
- Erro para o cliente é genérico; o detalhe vai para o log do servidor.
- Log é imutável do ponto de vista da aplicação: a app escreve, não apaga.

## Rate limit no código

A camada de borda deve limitar (ver `infra.md`), mas o login limita também na
aplicação, por conta e não só por IP — NAT faz muita gente compartilhar IP, e
atacante troca de IP.

Bloqueio progressivo (atraso crescente) funciona melhor que bloquear a conta,
que vira negação de serviço contra o usuário legítimo.
