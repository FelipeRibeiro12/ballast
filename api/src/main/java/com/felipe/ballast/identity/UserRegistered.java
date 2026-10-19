package com.felipe.ballast.identity;

import java.util.UUID;

// Publicado de forma síncrona, dentro da transação do cadastro. Quem escuta grava na mesma
// transação, e se falhar o usuário também não é criado.
public record UserRegistered(UUID userId) {}
