# ADR-050: MFA por TOTP, na F20

**Status:** aceito
**Data:** 2026-10-24

## Contexto

A F20 pede:
- TOTP;
- segredo cifrado;
- código de recuperação guardado em hash;
- limite de tentativa.

O `docs/ameacas.md` lista o segredo de MFA como ativo de sensibilidade alta.
Também lista a força bruta no MFA: seis dígitos caem rápido sem limite por
conta.

O dono decidiu três pontos em 2026-10-24:
- o MFA é **opcional**, e cada usuário liga o seu;
- a chave que cifra o segredo vem da variável **`MFA_CHAVE`**;
- a tela de ativação mostra **QR code**, com a biblioteca `uqr`.

## Decisão

### TOTP

- RFC 6238 com HMAC-SHA1, passo de 30 s e 6 dígitos. São os parâmetros que
  todo autenticador aceita sem configurar.
- Implementado com o JDK (`Mac`, `SecureRandom`), sem dependência nova. O
  teste confere os vetores da própria RFC.
- O segredo tem 20 bytes e sai em base32, sem `=`.
- Aceita um passo antes e um depois, para relógio de celular adiantado ou
  atrasado em até 30 s. Os três passos são comparados em tempo constante, sem
  parar no primeiro.
- **O mesmo código não entra duas vezes.** `mfa.ultimo_passo` guarda o passo do
  último código aceito, e só entra passo mais novo. Quem viu o código por cima
  do ombro não entra depois do dono. O código da ativação também conta como
  usado.

### Segredo cifrado

- AES-256-GCM com a chave de `MFA_CHAVE`: 32 bytes em base64, gerados com
  `openssl rand -base64 32`.
- O banco guarda o nonce de 12 bytes, os 20 do segredo cifrados e a tag de 16.
  São 48 bytes, e um `CHECK` garante o tamanho.
- O id do usuário entra como dado autenticado do GCM. Uma linha copiada para
  outro usuário não abre.
- Chave em formato errado derruba a subida. A mensagem descreve o formato e
  nunca mostra o valor.
- **Sem `MFA_CHAVE`, a API sobe.** Configurar MFA responde 503
  `mfa indisponível`. Quem já tem MFA ligado só entra com código de
  recuperação. É o mesmo efeito de perder a chave: por isso o runbook pede uma
  cópia num gerenciador de senhas.
- Trocar a chave invalida todos os segredos. Não há rotação com duas chaves.
  Com poucos usuários, o caminho é cada um entrar com um código de
  recuperação, desligar e ligar de novo.

### Códigos de recuperação

- Dez por ativação. Cada um tem 10 caracteres de um alfabeto de 31 (sem 0, 1,
  i, l e o), uns 50 bits.
- A tela mostra no formato `xxxxx-xxxxx`. A API aceita com espaço, hífen ou
  maiúscula.
- O banco guarda o SHA-256, como o token de sessão (ADR-015). Pelo mesmo
  motivo do ADR-015, o hash é rápido: o código é aleatório, então não há lista
  de candidatos para testar offline.
- Cada código entra uma vez (`usado_em`). O uso vai para o log, com quantos
  restam.
- Os códigos só aparecem em claro na resposta da ativação. Para ter códigos
  novos, desligue e ligue o MFA de novo.

### Login em duas etapas

1. O `POST /auth/login` com a senha certa e MFA ligado responde 202
   `{"mfaPendente":true}`. Ele não abre sessão: põe o cookie `ballast_mfa`,
   com as mesmas flags da sessão e 5 minutos de prazo.
2. O `POST /auth/login/mfa` com `{"codigo"}` lê esse cookie. Com o código
   certo, abre a sessão como o login normal e expira o `ballast_mfa`.

- O desafio fica na tabela `desafio_mfa`, só como SHA-256, igual à sessão.
- O desafio é de uso único: o `DELETE` decide quem ganha entre duas respostas
  certas ao mesmo tempo.
- Cada usuário tem no máximo um desafio aberto, e o login novo apaga o
  anterior.
- Código errado mantém o desafio dentro dos 5 minutos. O que segura a força
  bruta é o atraso por usuário, descrito abaixo.
- O 202 só sai depois da senha certa. Por isso ele não revela quem tem MFA a
  quem não sabe a senha.

### Limite de tentativa

- **Atraso progressivo por usuário**, na linha do `mfa`. A regra é a mesma do
  login da F1 (`LoginAttempts`): 3 falhas livres, depois 2^(n−3) segundos, até 15 minutos.
- A falha é contada antes de conferir, num `UPDATE` condicional (fail-closed).
  Trocar de IP ou pedir desafio novo não zera a conta. Código certo zera.
- No teto, sobram uns 4 palpites por hora. Com 3 códigos válidos em um milhão,
  a chance de acertar num dia fica perto de 0,03%.
- O limite por IP da F20.5 (ADR-049) também vale. `/auth/login/mfa` e as rotas
  `/auth/mfa/configurar`, `/ativar` e `/desativar` contam como login: 10 por
  minuto por IP e o teto somado de 60.

### Ligar e desligar

- `POST /auth/mfa/configurar` pede a senha. Sem isso, quem roubou só a sessão
  ligaria o MFA com o celular dele e trancaria o dono do lado de fora.
- Configurar gera um segredo pendente e não liga nada. Chamar de novo troca o
  pendente. O ativo nunca é trocado.
- `POST /auth/mfa/ativar` só aceita o código do autenticador. Isso prova que o
  segredo foi lido certo. A resposta traz os códigos de recuperação.
- `POST /auth/mfa/desativar` pede senha **e** código, do app ou de
  recuperação. Quem tem só a sessão, ou só o celular, não desliga.
- Senha errada nessas rotas não entra no atraso do login. O limite por IP
  segura, e a rota já exige sessão.
- Log de segurança: configurar, ativar, desativar, login com MFA, recusa do
  código e uso de código de recuperação. Com usuário e IP, sem código nem
  segredo.

### Web

- A tela **Segurança** fica fora da barra de seções e abre pelo ícone de
  escudo no topo. Até 400px o botão Sair fica só com o ícone, para caber.
- A ativação mostra o QR code e o segredo em grupos de 4 para digitar.
- **`uqr` 0.1.3** (MIT, sem dependências, da organização unjs, versão de
  2026-04): gera a matriz do QR no navegador. O segredo não sai do app nem
  passa por serviço de terceiros.
- O SVG é montado em React a partir da matriz (`encode`). O `renderSVG` com
  `innerHTML` não é usado.
- O QR fica preto no branco nos dois temas: muito leitor de celular não lê QR
  invertido.
- O login ganha a etapa "Código de verificação". Desafio vencido volta para a
  senha com aviso.

## Consequências

- `MFA_CHAVE` entra no `api.env` da VM e no `.env` local. O runbook manda
  guardar uma cópia fora da VM.
- Ligar o MFA não derruba as outras sessões abertas. Elas morrem no prazo de 7
  dias. Revogar as outras sessões ao ligar o MFA ficou no backlog.
- Não há "lembrar este aparelho". Todo login com MFA pede código.
- O atraso por e-mail do login (M2 da F1) continua como estava. Com MFA ligado,
  a senha sozinha não abre sessão, e a dívida perde peso.
