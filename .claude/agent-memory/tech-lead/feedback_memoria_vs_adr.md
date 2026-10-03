---
name: feedback-memoria-vs-adr
description: Regra do usuário sobre o que vai na memória do agente e o que vai em ADR
metadata:
  type: feedback
---

Memória de agente guarda **onde as coisas estão e armadilhas encontradas**. Decisão de registro (escolha de ferramenta, política, tradeoff) vai para `docs/adr-NNN.md`, não para a memória.

**Why:** a memória é versionada, mas só o ADR é o lugar onde decisão é lida e questionada. Decisão escondida em memória some da vista de quem revisa.
**How to apply:** ao tomar uma decisão de arquitetura ou de ferramenta, abrir o ADR (skill `/adr`) e deixar na memória só o ponteiro. Em [[project-bootstrap]], a seção "Decisões" ainda tem itens que deveriam migrar para ADR (oxlint no lugar de ESLint, golangci-lint fixado por brew, separação ci.yml/seguranca.yml): migrar quando o usuário pedir os ADRs.
