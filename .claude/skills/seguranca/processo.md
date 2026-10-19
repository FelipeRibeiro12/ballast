# Segurança — categoria processo

Não é código nem configuração: é o que o time faz de forma recorrente. Verifica-
se olhando o histórico e o pipeline, não um arquivo. Dono: o time.

## Dependências

- Lockfile commitado. CI com `--frozen-lockfile` — senão o CI instala versão
  diferente da sua e mascara o problema.
- Auditoria rodando **no pipeline**, falhando o build em vulnerabilidade alta
  ou crítica.
- Atualização de segurança entra rápido; atualização de versão maior entra
  planejada. São duas coisas diferentes e viram dois tipos de PR.
- Dependência nova passa por decisão: quem mantém, com que frequência atualiza,
  quantas dependências ela arrasta. Pacote abandonado é dívida de segurança.
- Robô de atualização (Dependabot, Renovate) ligado, com agrupamento — senão
  viram 40 PRs e ninguém olha nenhum.

```bash
npm audit --omit=dev        # ou pnpm audit --prod
govulncheck ./...           # Go
```

## Migrations e rollback

- **Migration aplicada nunca é editada.** Escreva outra. Editar quebra o
  histórico de quem já rodou.
- Todo `up` tem `down`, ou o comentário dizendo como desfazer.
- Mudança destrutiva em passos separados e reversíveis: adiciona nullable →
  preenche → torna obrigatória. Nunca num passo só.
- **Backup antes de migration em produção**, sempre, sem exceção.
- Deploy e migration são reversíveis de forma independente. Se o código novo
  depende da coluna nova, a ordem é: migration primeiro, código depois — e o
  código antigo tem que continuar funcionando com a coluna nova.
- O comando de rollback de deploy está escrito e foi testado uma vez.

Isso é o que permite reverter às 3 da manhã sem decidir nada.

## Gate no pipeline

O item de maior retorno de toda a lista. Use `ci-seguranca.yml` desta pasta.

- Varredura de segredo em todo PR, incluindo o histórico
- Auditoria de dependência, falhando em alta e crítica
- Análise estática (SAST) na linguagem do projeto
- Build falhando de verdade — gate que só avisa é ignorado em duas semanas

## Branch protection

Sem isso, todo o resto é contornável:

- Pipeline verde obrigatório para mergear
- Uma aprovação humana
- Push direto na `main` proibido, para todo mundo
- Histórico linear se o time preferir

Confirme no repositório, não presuma:

```bash
gh api repos/{owner}/{repo}/branches/main/protection
```

## Revisão

- Diff que mexe em autenticação, autorização, criptografia, upload ou
  dependência passa pelo agente `security` **antes** da revisão humana.
- PR pequeno. PR de 40 arquivos é aprovado sem leitura — e é nele que o
  problema passa.
- Achado de segurança bloqueante não vira "dívida": ou corrige, ou o ADR
  registra explicitamente o risco aceito, com quem aceitou.

## Dado pessoal

Se o sistema guarda dado de pessoa real, a LGPD se aplica — e isso é decisão de
projeto, não detalhe técnico:

- Que dado pessoal é coletado, para quê, e por quanto tempo fica
- Base legal de cada coleta
- Como o titular pede acesso e exclusão — e se existe o caminho técnico
- Minimização: não colete o que você não vai usar. É a medida mais eficaz, e a
  mais barata
- Quem mais recebe esse dado (terceiro, analytics)
- Plano de resposta a incidente: prazo de comunicação e para quem

Registre em `docs/dados-pessoais.md` e faça um ADR por decisão.

## Revisão periódica

- Releia `docs/seguranca.md` em intervalo fixo — e sempre antes de uma entrega.
- Teste de restauração de backup: anote a data da última.
- Ensaio do plano de recuperação: anote a data do último.
- Revisão de quem tem acesso a quê: tirar acesso de quem saiu é o item mais
  esquecido de toda a lista.

Item sem data da última verificação é item não verificado, mesmo que esteja
marcado como ok.
