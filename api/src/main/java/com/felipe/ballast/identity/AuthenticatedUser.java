package com.felipe.ballast.identity;

import java.util.UUID;

// Única origem do usuário nos controllers: vem da sessão no banco, nunca de corpo, query ou
// cabeçalho do cliente.
public record AuthenticatedUser(UUID id) {}
