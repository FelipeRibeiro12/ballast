# Segurança — Ballast

<!-- Template. Copie para docs/seguranca.md do projeto e preencha.
     Status permitido: ok | parcial | falta | n/a | não verificado
     "ok" exige evidência: arquivo e linha, ou o comando que comprova.
     Sem evidência, o status é "não verificado". -->

**Última revisão:** AAAA-MM-DD
**Responsável:** <quem>

Legenda de status: `ok` · `parcial` · `falta` · `n/a` · `não verificado`

## Código — verificável lendo o repositório

| # | Item | Status | Evidência | Dono |
|---|---|---|---|---|
| 1 | Validação de entrada em toda rota pública | não verificado | | backend |
| 2 | Campo não declarado é rejeitado (whitelist) | não verificado | | backend |
| 3 | Escape na saída / sanitização de HTML | não verificado | | frontend |
| 4 | Query sempre parametrizada, todo `raw` inspecionado | não verificado | | backend, dba |
| 5 | Senha com argon2 / bcrypt / scrypt e custo configurado | não verificado | | backend |
| 6 | Sessão e token com expiração; logout invalida no servidor | não verificado | | backend |
| 7 | Cookie `HttpOnly`, `Secure`, `SameSite` | não verificado | | backend |
| 8 | MFA no login e em ação sensível, com limite de tentativa | não verificado | | backend |
| 9 | Toda rota não pública verifica permissão **no recurso** | não verificado | | backend |
| 10 | Menor privilégio: usuário do banco, chaves, tokens | não verificado | | backend, dba |
| 11 | Criptografia com biblioteca padrão; chave no cofre | não verificado | | backend |
| 12 | Log de segurança sem dado sensível | não verificado | | backend |
| 13 | Erro genérico para o cliente, detalhe só no log | não verificado | | backend |

## Infra — exige confirmação no ambiente

| # | Item | Status | Evidência | Dono |
|---|---|---|---|---|
| 14 | HTTPS em todo tráfego, redirect, certificado renovando | não verificado | | devops |
| 15 | CORS com lista fechada de origens | não verificado | | devops |
| 16 | Rate limit na borda, mais rígido no login | não verificado | | devops |
| 17 | Secrets em cofre, por ambiente, com rotação prevista | não verificado | | devops |
| 18 | Banco fora da internet; contêiner não root; portas mínimas | não verificado | | devops |
| 19 | Backup automático, fora do servidor, cifrado | não verificado | | devops |
| 20 | **Restauração de backup testada** — data: ______ | não verificado | | devops |
| 21 | Monitoramento com alerta que chega em alguém | não verificado | | devops |
| 22 | Plano de recuperação escrito, com RTO e RPO | não verificado | | devops |
| 23 | **Plano de recuperação ensaiado** — data: ______ | não verificado | | devops |
| 24 | Cabeçalhos de segurança (HSTS, CSP, nosniff, frame) | não verificado | | devops |

## Processo — verificável no pipeline e no histórico

| # | Item | Status | Evidência | Dono |
|---|---|---|---|---|
| 25 | Lockfile commitado, CI com `--frozen-lockfile` | não verificado | | devops |
| 26 | Auditoria de dependência no CI, falhando em alta/crítica | não verificado | | devops |
| 27 | Varredura de segredo no CI, incluindo histórico | não verificado | | devops |
| 28 | Análise estática (SAST) no CI | não verificado | | devops |
| 29 | **Branch protection**: pipeline verde + 1 aprovação, sem push direto | não verificado | | tech-lead |
| 30 | Migration com rollback; backup antes de aplicar em produção | não verificado | | dba |
| 31 | Comando de rollback de deploy escrito e testado | não verificado | | devops |
| 32 | Diff de auth/cripto/upload passa pelo agente `security` | não verificado | | tech-lead |
| 33 | Revisão de acessos: tirar acesso de quem saiu — data: ______ | não verificado | | tech-lead |

## Dado pessoal (LGPD), se aplicável

| # | Item | Status | Evidência | Dono |
|---|---|---|---|---|
| 34 | Inventário: que dado, para quê, por quanto tempo | não verificado | | tech-lead |
| 35 | Minimização: não coletar o que não se usa | não verificado | | tech-lead |
| 36 | Caminho técnico para acesso e exclusão pelo titular | não verificado | | backend |
| 37 | Plano de resposta a incidente com prazo de comunicação | não verificado | | tech-lead |

## Riscos aceitos

Risco aceito consciente é engenharia; risco esquecido é acidente. Cada linha
aqui precisa de um ADR.

| Item | Risco | Por que foi aceito | ADR | Quem aceitou |
|---|---|---|---|---|
| | | | | |

## Pendências com prazo

| Item | O que falta | Dono | Até quando |
|---|---|---|---|
| | | | |
