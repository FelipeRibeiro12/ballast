# Modelo de ameaças

Feito antes de existir código, conforme `/sdlc-brainstorm`. Dez minutos aqui
evitam a refatoração de autorização que custa uma semana.

O checklist completo de segurança fica em `docs/seguranca.md`, gerado por
`/seguranca`.

## Que dado sensível o sistema guarda

| Dado | Sensibilidade | Se vazar |
|---|---|---|
| E-mail e senha | alta | senha reaproveitada em outros serviços |
| Histórico financeiro completo | **muito alta** | renda, hábitos, rotina, onde a pessoa está e quando |
| Posição em cripto | **muito alta** | identifica quem vale a pena atacar ou extorquir |
| Segredo de MFA | alta | o segundo fator deixa de valer |

O histórico financeiro é mais revelador que a senha. Lançamento com data,
valor, categoria e descrição reconstrói rotina: onde a pessoa almoça, que dia
recebe, quando viaja. Um app de finanças com dado de 50 pessoas é alvo mais
interessante que a soma dos saldos sugere.

**O que o app não guarda, por decisão:** dado de cartão (número, CVV),
credencial de banco, chave privada ou seed de carteira, e arquivo de extrato
depois de processado. Não há recurso que justifique o risco. Se algum dia
precisar, exige ADR e provavelmente um provedor certificado.

**Endereço de carteira é dado sensível apesar de ser público.** O endereço em
si está na blockchain e qualquer um consulta. Mas o app guarda a **associação
entre a pessoa e o endereço** — e é isso que transforma "uma carteira com X" em
"o Felipe tem X". Associação vazada é risco físico, não só financeiro.
Consequência: endereço nunca aparece em log, nunca em URL, nunca em analytics.

## Perfis e o que cada um não pode ver

Só existe um perfil: **usuário comum**. Não há administrador no produto.

O que cada usuário **não** pode: ver, editar ou apagar qualquer linha de outro
usuário. Nenhuma exceção, nenhuma rota de suporte que contorne.

Isso faz a autorização ser simples de descrever e fácil de errar: o erro não é
"permissão complexa", é **esquecer o filtro por `usuario_id` numa query**.

Consequência prática, que vira regra de implementação:

- Todo acesso ao banco parte do `usuario_id` da sessão
- Nunca confiar em identificador vindo do cliente para decidir dono
- `GET /lancamentos/{id}` filtra por `usuario_id` **e** por `id`, nunca só por
  `id`
- Teste de autorização em toda rota: logar como A e pedir recurso de B deve
  dar 404

Essa última linha é a mais importante do documento. Ela é o teste que pega a
falha mais comum em app multiusuário.

## Superfície exposta

- API HTTP pública (toda a internet)
- PWA servido como estático
- Chamada de saída para a API de cotação — **entrada de dado não confiável**:
  resposta inesperada ou gigante não pode derrubar o worker
- Postgres: **não exposto**, acesso só de dentro da rede

## O que para o negócio se cair

| Parte | Impacto | Urgência |
|---|---|---|
| Banco de dados | perda de dado = perda do produto | crítica |
| API | ninguém usa | alta |
| Worker de cotação | patrimônio em cripto fica desatualizado | baixa |
| PWA | mesmo que a API | alta |

Primeira versão de RTO e RPO, a confirmar na fatia de infra:

- **RPO** — aceito perder no máximo 24h de lançamento (backup diário).
  Reinserir um dia de gasto é chato; reinserir um ano é perder o usuário.
- **RTO** — 4h para voltar. É projeto pessoal, não tem plantão.

Perda de dado é pior que indisponibilidade: o app fora do ar por um dia irrita;
o histórico perdido mata o produto, porque o valor dele é justamente o
acumulado.

## Dado pessoal e LGPD

Sim, aplica a partir do momento em que existir um usuário que não seja eu.
Dado financeiro de pessoa identificada é dado pessoal, e não há base de
"legítimo interesse" confortável aqui: a base é **consentimento e execução de
contrato** com o titular.

Decisões que isso impõe:

- **Minimização** — não coletar o que não se usa. Não pedir CPF, telefone,
  endereço, data de nascimento. E-mail e senha bastam. É a medida mais eficaz e
  a mais barata.
- **Exclusão** — o titular precisa poder apagar a conta e tudo que é dele, de
  verdade. Precisa existir caminho técnico, não só promessa.
- **Acesso** — exportação do próprio dado em formato legível.
- **Terceiro** — a API de cotação **não** recebe dado do usuário. Só o símbolo
  do ativo. Nenhum analytics que identifique pessoa.
- **Incidente** — plano com prazo de comunicação ao titular e à ANPD.

Detalhar em `docs/dados-pessoais.md` antes de abrir para a primeira pessoa que
não seja eu — não depois.

## Abusos específicos deste domínio

Coisas que não são falha genérica de web, mas falha desta aplicação:

- **Enumerar usuário pelo login.** Mensagem diferente para "e-mail não existe"
  e "senha errada" entrega quais e-mails têm conta. A mensagem é a mesma,
  sempre.
- **Força bruta no login e no MFA.** Seis dígitos caem rápido sem limite de
  tentativa. Limite por conta, não só por IP.
- **Valor negativo ou gigante.** `valor` sempre positivo e com teto sensato. O
  sinal vem do tipo, não do número.
- **Lançamento em conta de outro usuário** passando o `conta_id` dele. A conta
  é validada contra o dono antes de qualquer gravação.
- **Saldo divergente por corrida.** Dois lançamentos simultâneos na mesma
  conta. Resolve-se calculando saldo por soma em vez de guardar — é por isso
  que o modelo não tem coluna de saldo.

## Importação de arquivo (F22, F23 e F26)

Arquivo enviado pelo usuário é entrada não confiável, mesmo sendo o próprio
usuário enviando.

- **Limite de tamanho e de número de linhas.** Arquivo de 500 MB ou com
  milhões de linhas derruba o processo.
- **Fórmula injetada em CSV** (`=`, `+`, `-`, `@` iniciando célula): se o app
  exportar depois para planilha, a fórmula executa na máquina de quem abrir.
  Prefixe com apóstrofo na exportação.
- **Zip bomb e PDF malformado.** Parser de PDF roda com limite de tempo e de
  memória, e falha fechando, não travando.
- **XXE em formato XML.** OFX tem variante XML: desabilitar entidade externa
  no parser. É a falha clássica desse formato.
- **Caminho de arquivo.** Nome do arquivo enviado nunca compõe caminho de
  disco. Salve com nome gerado.
- **Isolamento por usuário.** Importação de A nunca cria lançamento para B.
  `conta_id` validado contra o dono antes de qualquer gravação.

## Carteira de cripto (F24–F25)

A regra que elimina a maior parte do risco: **o app nunca pede assinatura de
transação.** Só endereço e, opcionalmente, assinatura de mensagem provando
posse. Aplicação de leitura que não pede assinatura de transação não tem como
drenar fundo nem aprovar gasto de token, mesmo comprometida.

- **Nunca pedir seed ou chave privada.** Não existe motivo legítimo. Aplicação
  que pede isso é indistinguível de golpe — e a interface deve dizer
  explicitamente que o app não pede.
- **Nunca `eth_sendTransaction`, nunca `approve`.** Se o código tiver qualquer
  chamada desse tipo, é bug de segurança, não recurso.
- **Validar o endereço antes de salvar**, incluindo o checksum do EIP-55 em
  EVM. Endereço digitado errado gera saldo zero inexplicável, e endereço
  parecido é vetor de confusão.
- **Resposta do indexador é dado de terceiro.** Quantidade absurda, decimais
  errados, símbolo com script embutido, resposta gigante. Validar e escapar.
- **Token de spam com cotação falsa** distorce o patrimônio. Resolvido por
  `confiavel = false` por padrão (ADR-009) — é mitigação de confiança no
  produto, não só estética.
- **Assinatura de posse precisa de nonce e prazo.** Mensagem SIWE sem nonce
  pode ser reusada.
- **Não gastar o endereço em log nem em URL.** Ver acima: a associação
  pessoa ↔ endereço é o dado sensível.
