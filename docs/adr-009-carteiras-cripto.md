# ADR-009: Carteiras de cripto — observar endereço, nunca custodiar

**Status:** aceito
**Data:** 2026-10-03

## Contexto

Pedido: conectar carteiras de cripto, com preferência por **Rabby** para redes
EVM e **Phantom** para Solana.

Há um mal-entendido comum que precisa ficar registrado, porque ele muda o
desenho: **conectar uma carteira não dá acesso a saldo nem a histórico.** O que
a conexão entrega é:

- o **endereço público** (`eth_requestAccounts`, ou o equivalente no Solana)
- opcionalmente, uma **assinatura** provando que o usuário controla aquele
  endereço (SIWE — Sign-In with Ethereum, ou mensagem assinada no Solana)

Saldo e histórico vêm de outro lugar: um nó RPC ou um indexador. E para
consultar isso, basta **conhecer o endereço** — que é público por natureza.

## Decisão

A funcionalidade é **observar endereço**, não "conectar carteira":

1. O usuário cadastra um endereço numa conta do tipo `cripto`. Duas formas,
   equivalentes em resultado:
   - **digitando/colando** o endereço — sempre disponível, funciona em qualquer
     lugar
   - **conectando a carteira**, que só preenche o endereço automaticamente e,
     se o usuário quiser, assina provando posse
2. Saldo e movimentação vêm de **RPC/indexador**, consultados pelo backend.
3. O app **nunca** solicita assinatura de transação. Nunca pede seed, nunca
   pede chave privada, nunca custodia nada.

Para a conexão, programar contra o **padrão**, não contra uma carteira
específica:

- **EVM:** `wagmi` v2 + `viem`, com descoberta via **EIP-6963** (Multi
  Injected Provider Discovery). Rabby implementa o padrão e aparece na lista de
  carteiras descobertas, junto com as outras que o usuário tiver. Rabby também
  oferece o RabbyKit, mas amarrar a interface a uma carteira específica é
  desnecessário.
- **Solana:** **Wallet Standard**, via `@solana/wallet-adapter` ou equivalente.
  Phantom se registra pelo padrão e aparece na lista.

Preferência do usuário por Rabby e Phantom se expressa na **ordem de exibição**
da lista, não em código acoplado a elas.

## Justificativa

Programar contra o padrão e não contra a carteira significa que Rabby e Phantom
funcionam, e qualquer outra também. Carteira muda API, muda nome do objeto
injetado, é descontinuada; o padrão permanece. Antes do EIP-6963 as aplicações
disputavam `window.ethereum` e a última extensão a carregar ganhava — o padrão
existe exatamente para resolver isso.

Aceitar endereço digitado como caminho de primeira classe tem três vantagens:
funciona no PWA instalado no celular (onde não existe extensão de navegador),
permite observar carteira de hardware ou de terceiro sem conectá-la, e torna a
conexão um conforto em vez de um requisito.

Não pedir assinatura de transação elimina a maior parte do risco. Uma
aplicação de leitura que nunca pede assinatura de transação não tem como
drenar fundo nem aprovar gasto de token, mesmo se for comprometida.

## Alternativas descartadas

- **Integrar o SDK de cada carteira separadamente** — mais código, mais
  manutenção, e o usuário fica restrito às carteiras previstas.
- **Exigir conexão de carteira para cadastrar endereço** — quebra no PWA
  instalado no celular e impede observar carteira que não está naquele
  dispositivo.
- **Pedir seed ou chave privada para ler saldo** — nunca. Não existe motivo
  legítimo, e qualquer aplicação que pede isso é indistinguível de golpe.
- **Conectar por API de corretora** — fora de escopo em `visao.md`.

## Consequência

- `ativo_cripto` passa a ter **rede** e **endereço de contrato**: `USDC` na
  Ethereum e `USDC` na Base são ativos diferentes, com endereços diferentes.
- `posicao_cripto` ganha `origem` (`manual` ou `sincronizada`): posição vinda
  da rede não é editável à mão, senão sincroniza e sobrescreve.
- "EVM" não é uma rede: Ethereum, Base, Arbitrum, Polygon, cada uma exige
  consulta própria. O MVP de carteira cobre Ethereum, Base e Solana; as outras
  entram por configuração, não por código novo.
- No celular, com o PWA instalado, não há extensão. A conexão ali é por
  WalletConnect ou pelo navegador interno da carteira — e é justamente por isso
  que digitar o endereço é caminho de primeira classe.
- O indexador é dependência externa que vai falhar. Vale a mesma regra da
  cotação (ADR-006): cache da última sincronização, data visível na interface,
  e nunca travar operação por indisponibilidade.

## Limitação reconhecida

**Preço de aquisição não existe na rede.** Uma transferência que entra na
carteira vinda de uma corretora não carrega o preço pago. Então a fatia de
"aporte × valorização" continua dependendo de o usuário informar o custo de
aquisição — a sincronização dá quantidade e movimentação, não custo.

**Token de spam vai poluir a carteira.** Airdrop de token sem valor e NFT
indesejado aparecem no saldo e, se tiverem cotação falsa, distorcem o
patrimônio. Daí a flag `confiavel` em `ativo_cripto`: por padrão, ativo
desconhecido entra oculto e o usuário promove o que interessa. Sem isso, a
primeira sincronização mostra um patrimônio errado e o usuário perde a
confiança no app.

## Em aberto

Escolha do indexador, na fatia correspondente. Candidatos: RPC público ou
Alchemy para EVM; RPC padrão ou Helius para Solana. Decidir por ADR próprio,
considerando plano gratuito e limite de requisição.
