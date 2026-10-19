# Segurança — categoria infra e deploy

**Nada aqui se verifica lendo o repositório.** Ou alguém confirma no ambiente,
ou o status é "não verificado". Dono: `devops`.

## HTTPS

- TLS em todo o tráfego público, com redirecionamento de HTTP para HTTPS.
- Certificado com renovação automática. Certificado que vence no domingo é
  incidente clássico.
- HSTS ligado depois de confirmar que tudo funciona em HTTPS.
- Dentro da rede privada, entre serviços, TLS também — se o custo permitir.

Como confirmar:

```bash
curl -sI http://seu-dominio.com | grep -i location    # redireciona?
curl -sI https://seu-dominio.com | grep -i strict-transport
```

## CORS

- **Lista fechada de origens.** Nunca `*` em API que usa credencial.
- `Access-Control-Allow-Origin: *` junto com `Allow-Credentials: true` é
  inválido e alguns navegadores silenciosamente ignoram — a combinação indica
  que ninguém entendeu a configuração.
- Métodos e cabeçalhos permitidos explícitos.
- Origem de desenvolvimento (`localhost`) não entra na lista de produção.
- CORS protege o navegador de outro site; **não** é controle de acesso. Um
  `curl` ignora CORS inteiramente. Rota mal autorizada continua exposta.

## Rate limit

- Na borda (proxy reverso, CDN, API gateway), antes de chegar na aplicação.
- Limite mais rígido em login, recuperação de senha e envio de e-mail.
- Limite por conta **e** por IP.
- Resposta `429` com `Retry-After`.
- Proteção contra carga volumétrica é da camada de rede, não da aplicação.

## Secrets

- Cofre de verdade: o gerenciador de segredos da plataforma, ou os secrets do
  CI. Não variável de ambiente colada à mão no painel sem registro.
- Rotação prevista, com prazo. Segredo que nunca rodou é segredo eterno.
- Quem tem acesso ao cofre é lista curta e revisada.
- Segredo de produção não existe na máquina de ninguém.
- Segredo diferente por ambiente. Chave de produção em staging é o atalho mais
  comum para vazamento.

## Menor privilégio na infra

- Cada serviço com sua identidade e só as permissões que usa. Nada de uma
  credencial de administrador compartilhada.
- Banco **não** exposto na internet. Acesso só de dentro da rede.
- Contêiner não roda como root. Sistema de arquivos somente leitura quando dá.
- Porta aberta é só a que precisa. Regra de firewall que ninguém sabe explicar
  é regra para remover.
- Acesso administrativo com MFA obrigatório.

## Backup

- Automático, com frequência coerente com o quanto de dado você aceita perder.
- **Em outro lugar** — outra conta ou outra região. Backup no mesmo servidor
  não é backup.
- Cifrado em repouso.
- **Restauração testada.** Esse é o item que separa backup de teatro: backup
  nunca restaurado é desconhecido, não garantia. Teste e anote a data.
- Prazo de retenção definido, e com uma cópia que a aplicação não consegue
  apagar — senão ransomware ou um `DELETE` errado leva o backup junto.

Pergunte explicitamente ao usuário: **quando foi a última restauração
testada?** Se a resposta for "nunca", o status do item é "falta", não "ok".

## Monitoramento

- Disponibilidade medida de fora, não só de dentro.
- Erro agrupado e visível, com volume e tendência (não só uma linha no log).
- Métrica mínima: taxa de erro, latência, uso de recurso, fila.
- **Alerta que chega em alguém**, com quem responde definido. Painel que
  ninguém abre não é monitoramento.
- Alerta de segurança separado: pico de falha de login, erro de autorização em
  série, uso anômalo.
- Pouco alerta e todos acionáveis. Alerta que todo mundo ignora é pior que
  nenhum, porque treina o time a ignorar.

## Plano de recuperação

Escrito, em `docs/recuperacao.md`, e curto o bastante para ser seguido às 3 da
manhã por quem está nervoso.

- **RTO** — quanto tempo até voltar. **RPO** — quanto dado você aceita perder.
  Os dois números decidem tudo o resto.
- Passo a passo de restauração, com os comandos reais.
- Como reverter um deploy ruim, com o comando.
- Quem avisar, em que ordem, e por qual canal — incluindo o canal que funciona
  quando o sistema principal está fora.
- Onde estão as credenciais de emergência e quem tem acesso.
- **Ensaiado pelo menos uma vez.** Plano nunca ensaiado é ficção.

## Cabeçalhos

Baratos e eficazes:

```
Strict-Transport-Security: max-age=31536000; includeSubDomains
X-Content-Type-Options: nosniff
X-Frame-Options: DENY            # ou frame-ancestors no CSP
Content-Security-Policy: default-src 'self'; ...
Referrer-Policy: strict-origin-when-cross-origin
```

E remova o cabeçalho que anuncia servidor e versão.

CSP dá trabalho para acertar sem quebrar a página: comece em modo de relatório,
veja o que quebraria, depois imponha.
