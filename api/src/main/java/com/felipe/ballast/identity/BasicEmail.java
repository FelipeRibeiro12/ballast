package com.felipe.ballast.identity;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Locale;
import java.util.regex.Pattern;

// Uma arroba, sem espaço, domínio com ponto, sem nenhum caractere Cf (nem ZWJ e ZWNJ, que os nomes
// aceitam) e já em minúsculas estáveis: EmailNormalizer deixa o e-mail em maiúscula quando o Java
// e o Postgres discordam do minúsculo (o "İ" turco), e aqui ele vira 400. Confirmar que o endereço
// existe, se um dia vier,
// é por e-mail e não por regex.
@Documented
@Constraint(validatedBy = BasicEmail.Validator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@interface BasicEmail {

  String message() default "e-mail inválido";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};

  class Validator implements ConstraintValidator<BasicEmail, String> {

    private static final Pattern FORMAT = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
      return value == null
          || (FORMAT.matcher(value).matches()
              && value.codePoints().noneMatch(cp -> Character.getType(cp) == Character.FORMAT)
              && value.equals(value.toLowerCase(Locale.ROOT)));
    }
  }
}
