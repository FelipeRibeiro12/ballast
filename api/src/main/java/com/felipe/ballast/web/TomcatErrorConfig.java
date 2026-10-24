package com.felipe.ballast.web;

import org.apache.catalina.core.StandardHost;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class TomcatErrorConfig {

  @Bean
  WebServerFactoryCustomizer<TomcatServletWebServerFactory> jsonErrorReport() {
    return factory ->
        factory.addContextCustomizers(
            context -> {
              if (context.getParent() instanceof StandardHost host) {
                host.setErrorReportValveClass(JsonErrorReportValve.class.getName());
              }
            });
  }
}
