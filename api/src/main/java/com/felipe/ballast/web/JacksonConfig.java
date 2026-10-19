package com.felipe.ballast.web;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;

@Configuration(proxyBeanMethods = false)
class JacksonConfig {

  // ALLOW_COERCION_OF_SCALARS não cobre String: o desserializador de texto consulta só a
  // configuração de coerção, e o padrão dela converte número e booleano em texto. Sem isto,
  // {"nome": 123} gravaria "123".
  @Bean
  JsonMapperBuilderCustomizer rejectScalarsInTextFields() {
    return builder ->
        builder.withCoercionConfig(
            LogicalType.Textual,
            config ->
                config
                    .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail));
  }
}
