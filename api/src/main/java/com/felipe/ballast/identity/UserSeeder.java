package com.felipe.ballast.identity;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

// Cria o usuário inicial. Passa pela mesma validação e normalização do cadastro pela API
// (RegisterRequest), porque o cadastro fica fechado por padrão e o seed é a porta de entrada
// do primeiro usuário.
@Component
class UserSeeder {

  record Result(boolean created, UUID userId) {}

  private static final Map<String, String> VARIABLES =
      Map.of("email", "SEED_EMAIL", "password", "SEED_SENHA", "name", "SEED_NOME");

  private final IdentityService identity;
  private final Validator validator;

  UserSeeder(IdentityService identity, Validator validator) {
    this.identity = identity;
    this.validator = validator;
  }

  // As mensagens nomeiam a variável e nunca trazem o valor: e-mail e senha não vão para a saída
  // nem para os erros.
  Result seed(String email, String password, String name) {
    if (email == null || email.isBlank() || password == null || password.isEmpty()) {
      throw new IllegalArgumentException("SEED_EMAIL e SEED_SENHA são obrigatórias");
    }
    RegisterRequest request = new RegisterRequest(email, password, name);
    var violations = validator.validate(request);
    if (!violations.isEmpty()) {
      throw new IllegalArgumentException(describe(violations));
    }
    Optional<UserProfile> created =
        identity.register(request.email(), request.password(), request.name());
    return new Result(created.isPresent(), created.map(UserProfile::id).orElse(null));
  }

  private static String describe(Set<ConstraintViolation<RegisterRequest>> violations) {
    return violations.stream()
        .sorted(Comparator.comparing(violation -> violation.getPropertyPath().toString()))
        .map(
            violation ->
                VARIABLES.get(violation.getPropertyPath().toString())
                    + ": "
                    + violation.getMessage())
        .collect(Collectors.joining("; "));
  }
}
