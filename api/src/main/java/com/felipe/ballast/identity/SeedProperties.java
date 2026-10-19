package com.felipe.ballast.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("ballast.seed")
public record SeedProperties(
    @DefaultValue("") String email,
    @DefaultValue("") String password,
    @DefaultValue("") String name) {

  @Override
  public String toString() {
    return "SeedProperties[email=***, password=***, name=***]";
  }
}
