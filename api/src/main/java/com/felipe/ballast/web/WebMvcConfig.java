package com.felipe.ballast.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
class WebMvcConfig implements WebMvcConfigurer {

  private final UnknownQueryParameterInterceptor unknownQueryParameters;

  WebMvcConfig(UnknownQueryParameterInterceptor unknownQueryParameters) {
    this.unknownQueryParameters = unknownQueryParameters;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(unknownQueryParameters);
  }
}
