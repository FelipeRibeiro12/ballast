---
name: armadilhas-job-cofrinho
description: Pegadinhas ao criar job agendado com flag e ao testar contra tabela indice compartilhada (pacote savings)
metadata:
  type: project
---

- Bean de job com `@Scheduled` precisa ser `@ConditionalOnProperty` na própria flag: qualquer `@EnableScheduling` do contexto (o das recorrências) registra o `@Scheduled` de todos os beans. Os testes têm a flag falsa, então chamam o serviço e fazem `new Job(service)`.
- `indice` (CDI) é compartilhada entre classes de teste: limpe só uma faixa de datas própria no setUp e no tearDown (ver `SavingsSupport`).
- `sed -i` no macOS exige `''`; um `sed` falho no meio de `&&` pula o `cat >` seguinte sem aviso.
- FK composta com `ON DELETE SET NULL (coluna)` (Postgres 15+) anula só a coluna listada; sem a lista anularia `usuario_id` NOT NULL.
- `MigrationsTest.appliesAllMigrationsInOrder` lista as versões: atualizar a cada migration nova.
