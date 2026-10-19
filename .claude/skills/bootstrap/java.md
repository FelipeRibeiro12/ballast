# Bootstrap — Java com Spring Boot

Confira `java -version` e `mvn -version` antes. Se divergir do que está aqui,
siga o que a ferramenta fez e avise.

## Versão

Spring Boot 4 aceita **Java 17 como mínimo**, mas 21 e 25 são os recomendados,
e é neles que virtual threads valem. Projeto novo nasce na **LTS atual**, não
no piso — começar no mínimo é nascer duas gerações atrás.

## Scaffold

Pelo Spring Initializr, sem instalar nada:

```bash
curl https://start.spring.io/starter.zip \
  -d type=maven-project \
  -d language=java \
  -d javaVersion=25 \
  -d bootVersion=4.0.0 \
  -d groupId=com.exemplo \
  -d artifactId=api \
  -d name=api \
  -d packageName=com.exemplo.api \
  -d dependencies=web,data-jpa,postgresql,flyway,validation,actuator \
  -o api.zip
unzip api.zip -d api && rm api.zip
cd api && ./mvnw -q verify
```

Confirme a versão do Boot em https://start.spring.io antes de fixar
`bootVersion` — a do comando envelhece.

**Maven ou Gradle:** Maven tem mais material, mais presença no mercado
corporativo brasileiro e `pom.xml` é mais fácil de ler para quem está
começando. Gradle compila mais rápido em projeto grande, o que você não tem.

O wrapper (`mvnw`) é commitado. Ele fixa a versão do Maven para a sua máquina
e para o CI — mesma lógica de fixar a versão de qualquer ferramenta.

## Estrutura

Pacotes por **domínio**, não por camada técnica:

```
src/main/java/com/exemplo/api/
├── ApiApplication.java
├── config/
├── contas/
│   ├── ContaController.java
│   ├── ContaService.java
│   ├── ContaRepository.java
│   ├── Conta.java            # entidade
│   └── dto/                  # records
└── lancamentos/
src/main/resources/
├── application.yml
└── db/migration/             # Flyway: V1__cria_usuarios.sql
src/test/java/...
```

`com.exemplo.api.contas.controller` espalha um domínio por quatro pastas e
junta coisas que não se relacionam. Por domínio, cada pasta é um pedaço que se
entende sozinho.

## Migrations — Flyway

SQL puro em `src/main/resources/db/migration`, nomeado
`V<n>__descricao_em_snake.sql`. Rodam na subida da aplicação, em ordem.

```yaml
spring:
  flyway:
    enabled: true
  jpa:
    hibernate:
      ddl-auto: validate
```

**`ddl-auto: validate`, nunca `update`.** Com `update` o Hibernate altera o
schema sozinho, sem migration e sem registro — e aí ninguém sabe mais qual é o
schema de verdade. Com `validate` ele confere que as entidades batem com o que
o Flyway construiu, e falha na subida se divergir. Isso é um gate de graça.

Migration aplicada não se edita: escreva outra.

## Configuração e segredo

`application.yml` com valores vindos do ambiente, sem padrão perigoso:

```yaml
spring:
  datasource:
    url: ${DATABASE_URL}
    username: ${DB_USER}
    password: ${DB_PASSWORD}
```

Sem segredo no arquivo. `application-dev.yml` para o que é só de
desenvolvimento, e nada de senha de produção em lugar nenhum do repositório.

## Teste

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-testcontainers</artifactId>
  <scope>test</scope>
</dependency>
<dependency>
  <groupId>org.testcontainers</groupId>
  <artifactId>postgresql</artifactId>
  <scope>test</scope>
</dependency>
```

Testcontainers sobe um Postgres de verdade para o teste de integração. H2 em
memória mente: tipo, constraint e dialeto divergem do que roda em produção, e
o teste passa onde a produção quebraria.

Exige Docker rodando — na máquina e no runner do CI. O runner do GitHub já
tem.

```bash
./mvnw test       # unitário
./mvnw verify     # tudo, incluindo integração
```

## Lint e formatação

Spotless com o estilo do Google resolve formatação sem discussão:

```bash
./mvnw spotless:apply    # formata
./mvnw spotless:check    # falha no CI se não formatado
```

Para análise além de formatação, o Error Prone pega bug real em tempo de
compilação. Entra quando o projeto crescer; não no primeiro dia.

## CI

```yaml
      - uses: actions/setup-java@v4
        with:
          java-version: '25'
          distribution: 'temurin'
          cache: maven
      - run: ./mvnw -B spotless:check
      - run: ./mvnw -B verify
```

`-B` tira a barra de progresso, que polui o log. `cache: maven` evita baixar o
mundo a cada execução.

## Critério de pronto

- [ ] `./mvnw verify` roda e passa, do zero
- [ ] Fica **vermelho** quando um teste é quebrado de propósito
- [ ] `spotless:check` passa
- [ ] Flyway cria o schema numa base vazia
- [ ] `ddl-auto` está em `validate`
- [ ] Nenhum segredo no repositório; `.env.example` ou `application-dev.yml`
      no lugar
- [ ] README com o passo a passo verificado
