---
name: feedback-memoria-vs-adr
description: Regra do usuário sobre o que vai na memória do agente e o que vai em ADR
metadata:
  type: feedback
---

Memória de agente guarda **onde as coisas estão e armadilhas encontradas**. Decisão de registro (escolha de ferramenta, política, tradeoff) vai para `docs/adr-NNN.md`, não para a memória.

**Why:** a memória é versionada, mas só o ADR é o lugar onde decisão é lida e questionada. Decisão escondida em memória some da vista de quem revisa.
Critério para abrir ADR: alguém perguntaria "por que isso é assim?" e a resposta não está óbvia onde a coisa mora. Convenção documentada no README, no compose ou no backlog não vira ADR (casos recusados: `HOST` em loopback, compose sem senha padrão, PWA como F9, `packageManager`, este último coberto pelo ADR-011).

**How to apply:** ao tomar uma decisão de arquitetura ou de ferramenta, abrir o ADR (skill `/adr`, que o modelo não invoca: o tech-lead escreve o arquivo seguindo o formato dela) e deixar na memória só o ponteiro. O `docs` não escreve ADR. Já migradas: oxlint, golangci-lint fixado e separação de workflows (ADR-010 a 012); ver [[project-bootstrap]].
