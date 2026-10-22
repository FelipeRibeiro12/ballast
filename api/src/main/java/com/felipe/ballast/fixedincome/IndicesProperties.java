package com.felipe.ballast.fixedincome;

import org.springframework.boot.context.properties.ConfigurationProperties;

// baseUrl é a raiz da API do SGS. O job baixa os índices que faltam ao subir e no cron, no fuso
// America/Sao_Paulo; os testes de integração desligam.
@ConfigurationProperties("ballast.indices")
public record IndicesProperties(String baseUrl, boolean jobEnabled, String cron) {}
