---
paths:
  - "api/**/*.java"
---

# Java e Spring Boot

## Dinheiro — as regras que mais quebram

- **`BigDecimal` sempre.** Nunca `double`, nunca `float`, nunca `Double`. Nem
  em variável intermediária, nem em DTO, nem em cálculo "só para exibir".
- **Construa com `String`**, nunca com `double`:
  `new BigDecimal("0.1")` é exato; `new BigDecimal(0.1)` guarda o lixo binário
  do `double` e já nasce errado.
- **`compareTo` para comparar, nunca `equals`.** `new BigDecimal("1.0")` não é
  `equals` a `new BigDecimal("1.00")` — a escala entra na comparação. Em app
  de dinheiro, esse é o bug mais caro e o mais silencioso.
- **`setScale` com `RoundingMode` explícito.** Divisão sem escala definida
  lança `ArithmeticException` em dízima. Escolha o modo e deixe escrito por
  quê.
- Arredondamento acontece **uma vez, na borda de saída**. Nunca no meio do
  cálculo, nunca duas vezes.
- No banco, `numeric` com escala definida. No JSON, string ou inteiro de
  centavos — nunca número decimal, porque o JavaScript do outro lado
  transforma em `double`.

## Data e hora

- `java.time` sempre. `Date`, `Calendar` e `SimpleDateFormat` não entram.
- `LocalDate` para data sem hora (competência, vencimento), `Instant` para
  momento no tempo (criação, expiração), `ZonedDateTime` só onde o fuso é
  parte da regra.
- Banco em UTC. Fuso é problema de exibição.
- `YearMonth` e `TemporalAdjusters` para regra de mês — `lastDayOfMonth()`
  resolve o dia 31 em fevereiro sem `if`.
- Teste as bordas: fevereiro, ano bissexto, mudança de horário, fim de mês.

## Nulos e erros

- `Optional` como **retorno** de método. Nunca como campo de entidade, nunca
  como parâmetro.
- Nada de `catch` vazio, nada de `catch` que só loga e segue.
- Exceção de domínio é classe própria, não `RuntimeException` genérica.
- `@ControllerAdvice` traduz exceção para resposta. O cliente recebe mensagem
  genérica; stack trace fica no log.

## Spring

- **Injeção por construtor**, com campo `final`. Nunca `@Autowired` em campo —
  esconde dependência e impede teste sem container.
- `record` para DTO. Imutável e sem boilerplate.
- **Nunca exponha entidade JPA na API.** Entidade é modelo de persistência;
  DTO é contrato. Misturar os dois acopla o schema ao cliente e vaza campo sem
  querer.
- `@Transactional` no serviço, nunca no controller. E saiba que ele não vale
  em chamada de método dentro da mesma classe — o proxy não intercepta.
- Camadas: controller recebe e devolve, serviço tem a regra, repositório toca
  o banco. Regra de negócio em controller é dívida desde o primeiro dia.
- Configuração por `@ConfigurationProperties` tipado, não `@Value` espalhado.

## JPA, onde ele morde

- **`FetchType.LAZY` em todo `@ManyToOne` e `@OneToOne`.** O padrão é `EAGER` e
  ele carrega meio banco sem avisar.
- **N+1**: laço sobre lista de entidades acessando relação carrega uma query
  por item. Resolva com `JOIN FETCH` ou `@EntityGraph`, e desconfie sempre que
  uma listagem ficar lenta.
- `equals` e `hashCode` em entidade com id gerado quebram em `Set` antes do
  flush. Use o id de negócio, ou não implemente.
- Consulta de agregação (relatório, saldo, gráfico) vai em SQL via
  `JdbcClient` ou query nativa. Ginástica de JPQL para somar e agrupar custa
  mais que o SQL direto e esconde o plano de execução.
- `spring.jpa.hibernate.ddl-auto` fica em `validate` ou `none`. **Nunca
  `update`** — ele altera schema sem migration e sem registro.

## Validação

- Bean Validation no DTO de entrada, com `@Valid` no controller.
- Validar tipo, faixa e **tamanho máximo**. Campo de texto sem limite é vetor
  de abuso.
- Validação de borda não substitui constraint no banco: duas requisições
  simultâneas furam validação feita só em código.

## Teste

- JUnit 5. `@SpringBootTest` só quando precisa do contexto inteiro; para
  serviço, teste direto com dublê.
- Integração contra Postgres de verdade, via Testcontainers. Banco em memória
  mente: tipo, constraint e SQL divergem do que roda em produção.
- Não mocke o que você está testando. Teste que mocka o próprio serviço não
  prova nada.
- Teste de dinheiro verifica **escala e soma exata**, não só "deu mais ou
  menos certo".

## Comandos

```bash
./mvnw spring-boot:run
./mvnw test
./mvnw verify
./mvnw -q -DskipTests package
```
