# Ballast

App de controle financeiro pessoal: fiat e cripto no mesmo lugar, com
recorrência, cartão de crédito parcelado, metas e projeção de saldo.

Contexto em `docs/visao.md`, decisões em `docs/adr-*.md`, modelo de dados em
`docs/contracts/dados.md` (**leia antes de mexer em tabela**). O que aconteceu
em cada fatia fica em `docs/diario.md`.

<!-- Mantenha abaixo de 200 linhas: arquivo grande consome mais contexto e
     reduz a aderência. Convenção de Java está em .claude/rules/java-spring.md
     e de banco em sql.md, que carregam só quando o arquivo é lido. -->

## Stack

- Java 25 com Spring Boot 4, monolito modular, Maven com o wrapper `mvnw`
  commitado (ADR-001, ADR-016)
- Postgres (ADR-003)
- Spring Data JPA, e SQL direto para agregação (ADR-017)
- Flyway para migrations (ADR-018)
- React + Vite como PWA, mobile-first (ADR-004)
- Monorepo: `api/` e `web/`
- Spec OpenAPI gerada do código por springdoc, commitada em
  `api/openapi.yaml`; o cliente TS vem dela (ADR-019)

Não troque nada disso sem pedir. Reverter ADR aceito é decisão do dono.

## Estrutura

Pacotes por domínio, não por camada técnica.

```
api/src/main/java/com/felipe/ballast/
├── BallastApplication.java
├── config/
├── contas/           # Controller, Service, Repository, entidade, dto/
├── lancamentos/
├── recorrencia/
├── cartao/
├── metas/
├── orcamento/
└── cotacoes/
api/src/main/resources/
├── application.yml
└── db/migration/     # Flyway: V1__cria_usuarios.sql
web/src/
```

## Dinheiro, regras absolutas

- **`BigDecimal` sempre.** Nunca `double`, nunca `float`, nem em variável de
  passagem. No banco, `numeric(18,2)` para fiat (cabe em `long` de centavos) e
  `numeric(38,18)` para cripto.
- **Construa com `String`**: `new BigDecimal("0.1")`. Construir com `double`
  guarda o lixo binário e já nasce errado.
- **Compare com `compareTo`, nunca `equals`.** `1.0` não é `equals` a `1.00`,
  porque a escala entra na conta. Esse é o bug mais silencioso do projeto.
- **`setScale` com `RoundingMode` explícito**, e o motivo da escolha no
  comentário. Divisão sem escala lança exceção em dízima.
- No JSON, **fiat em inteiro de centavos, cripto em string**. Nunca número
  decimal, que o JavaScript vira `double`. Fiat converte no DTO com
  `longValueExact()`. O frontend tem uma regra para cada tipo.
- Arredondamento acontece **uma vez, na borda de saída**.
- Divisão com resto (parcela, percentual de salário): resto na última parcela,
  e a soma tem que fechar com o total. Detalhes no ADR-005.

## As duas datas

Todo lançamento tem `data_competencia` (quando o fato aconteceu) e `data_caixa`
(quando o dinheiro se move). Em débito são iguais, em cartão não.

- Gráfico por categoria e orçamento usam **competência**
- Saldo e saldo projetado usam **caixa**

Confundir as duas é o bug mais provável deste projeto.

## Autorização

**Toda tabela tem `usuario_id` e toda query é escopada por ele.** É a única
fronteira de autorização do app.

- Busca por id filtra por `usuario_id` **e** `id`, nunca só por `id`
- Nunca confie em identificador vindo do cliente para decidir dono
- Conta informada num lançamento é validada contra o dono antes de gravar
- Toda rota tem teste: logar como A e pedir recurso de B deve dar 404
- Ameaças em `docs/ameacas.md`

## JPA

`ddl-auto` fica em `validate`. **Nunca `update`**: ele altera schema sem
migration e sem registro, e aí ninguém sabe mais qual é o schema de verdade.

Entidade JPA nunca é exposta na API. Agregação vai em SQL, não em JPQL. As
outras armadilhas estão em `.claude/rules/java-spring.md`.

## Cripto, limites absolutos

O app **observa** endereço. Não é carteira (ADR-009).

- Nunca pedir seed ou chave privada, e a interface diz que o app não pede
- Nunca assinatura de transação. Só endereço e, opcional, assinatura de
  mensagem provando posse
- Programe contra o padrão, não contra a carteira: EIP-6963 no EVM, Wallet
  Standard no Solana
- Digitar o endereço é caminho de primeira classe e funciona sem extensão
- Identidade do ativo é `rede` + `contrato`, nunca o símbolo
- Endereço não vai para log nem para URL. O dado sensível é a associação
  pessoa com endereço

O cliente do indexador vive dentro do monolito. Não existe serviço separado
para web3.

## Importação é entrada não confiável

Mesmo sendo o próprio usuário enviando: limite de tamanho e de linhas, XXE
desabilitado no parser de OFX em XML, fórmula escapada em CSV, nome de arquivo
nunca compondo caminho de disco.

Toda importação passa por revisão. Nada entra no extrato sem confirmação
(ADR-008).

## Saldo não é coluna

Saldo é `saldo_inicial` mais a soma dos lançamentos realizados. Guardar saldo
materializado é a origem clássica de divergência e de corrida entre duas
requisições. Se a performance exigir, vira view materializada com ADR.

## Como trabalhar

**Fazer funcionar primeiro. Melhorar depois que foi aceito.**

1. Teste que falha, pelo motivo certo
2. A versão mais simples que passa. Pode ser feia, pode ser hardcoded
3. Suíte verde, commit
4. Entrega

Melhoria estrutural não segura entrega. Ela vira linha no `docs/backlog.md`,
com dono e com a fatia que vai pagar. "Melhoro depois" sem endereço nunca
acontece.

**Quem libera é o pipeline verde, não opinião sobre qualidade.** Só achado que
bloqueia segura o merge: quebra, furo de segurança, perda de dado, contrato
violado, teste ausente onde era o combinado.

Uma fatia por vez. Não adiante a próxima.

## Testes que não podem faltar

Os que pegam os bugs reais deste domínio:

- Recorrência: dia 31 em fevereiro, ano bissexto, intervalo maior que 1,
  materialização rodada duas vezes (idempotência)
- Divisão: três partes de 33,33% de R$ 1.000 somando exatamente R$ 1.000
- Cartão: fechamento dia 31 em fevereiro, vencimento menor que fechamento,
  compra no próprio dia do fechamento
- Autorização: usuário A pedindo recurso de B, em toda rota
- Cotação: API fora do ar não trava lançamento
- Integração contra Postgres real, por Testcontainers. Banco em memória mente
  sobre tipo e constraint

## Git

- Conventional commits: `feat:`, `fix:`, `test:`, `refactor:`, `docs:`,
  `chore:`. Mensagem em português, no imperativo, sem travessão
- Um commit por passo lógico
- Não commite com teste quebrado
- Fatia fecha com `/pr`, ou dentro do `/piloto`

## Comandos

```bash
make dev        # ./mvnw spring-boot:run
make test       # ./mvnw verify
make lint       # ./mvnw spotless:check (migration roda na subida, via Flyway)
cd web && pnpm dev
cd web && pnpm test
```

## Regras

- Instale a dependência que precisar. Confira que é mantida, que não cobra e
  que a licença serve. Registre em ADR e me avise no resumo, não antes.
- Migration aplicada não se edita: escreva outra.
- Mudança no modelo atualiza `docs/contracts/dados.md` no mesmo PR.
- Não gere chave privada, nem coloque chave, seed ou `.env` no repositório.
- **Decida sozinho e registre em ADR.** Pare só na lista fechada do seu
  arquivo de agente: irreversível e caro, segredo no histórico, desligar uma
  grade, contrariar ADR aceito, terceira tentativa no mesmo problema, fatia
  que explodiu, ou eu pedindo.
- Se eu pedir algo que vai dar problema mais na frente, fale agora.

## A completar

Não invente conteúdo para estes. Se precisar, pergunte.

- RTO e RPO confirmados (primeira versão em `docs/ameacas.md`; o backup de
  produção é semanal, ADR-044)

Decididos pelo dono: provedor de cotação (CoinGecko Demo, ADR-040) e
hospedagem em tier gratuito (Oracle, Neon, Vercel, ADR-044, com o passo a
passo em `docs/runbook-producao.md`).
