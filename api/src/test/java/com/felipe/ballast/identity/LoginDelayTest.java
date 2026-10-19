package com.felipe.ballast.identity;

import static com.felipe.ballast.identity.TestUsers.PASSWORD;
import static com.felipe.ballast.identity.TestUsers.SESSION_COOKIE;
import static org.assertj.core.api.Assertions.assertThat;

import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.TestClock;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class LoginDelayTest {

  private static final String WRONG = "senha-errada-12345";
  private static final String TOO_MANY = "{\"erro\":\"muitas tentativas, tente mais tarde\"}";

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired TestClock clock;
  private TestUsers users;

  @BeforeEach
  void setUp() {
    users = new TestUsers(mvc);
    clock.freeze(Instant.parse("2026-03-10T12:00:00Z"));
  }

  @AfterEach
  void tearDown() {
    clock.reset();
  }

  private Optional<Integer> failuresOf(String email) {
    return jdbc.sql("SELECT falhas FROM tentativa_login WHERE email = :email")
        .param("email", email)
        .query(Integer.class)
        .optional();
  }

  private void seedAttempts(String email, int failures) {
    jdbc.sql("INSERT INTO tentativa_login (email, falhas, ultima_falha_em) VALUES (?, ?, ?)")
        .param(email)
        .param(failures)
        .param(clock.instant().atOffset(ZoneOffset.UTC))
        .update();
  }

  private void assertTooMany(MockHttpServletResponse response, String retryAfter) throws Exception {
    assertThat(response.getStatus()).isEqualTo(429);
    assertThat(response.getHeader("Retry-After")).isEqualTo(retryAfter);
    assertThat(response.getContentAsString(StandardCharsets.UTF_8)).isEqualTo(TOO_MANY);
    assertThat(response.getCookie(SESSION_COOKIE)).isNull();
  }

  @Test
  void threeFailuresHaveNoDelayAndTheFourthStartsIt() throws Exception {
    TestUsers.Registered user = users.register();

    // A 4ª tentativa ainda é verificada: o atraso nasce da 4ª falha, não antes dela.
    for (int attempt = 1; attempt <= 4; attempt++) {
      assertThat(users.login(user.email(), WRONG).getStatus())
          .as("tentativa " + attempt)
          .isEqualTo(401);
    }

    assertTooMany(users.login(user.email(), WRONG), "2");
  }

  @Test
  void duringTheDelayItNeitherCountsNorChecksThePassword() throws Exception {
    TestUsers.Registered user = users.register();
    for (int i = 0; i < 4; i++) {
      users.login(user.email(), WRONG);
    }

    for (int i = 0; i < 5; i++) {
      assertTooMany(users.login(user.email(), WRONG), "2");
    }
    // Com a senha certa também: se verificasse, abriria sessão.
    assertTooMany(users.login(user.email(), PASSWORD), "2");

    assertThat(failuresOf(user.email())).contains(4);
  }

  @Test
  void afterTheDelayItChecksAgainAndTheNextDelayDoubles() throws Exception {
    TestUsers.Registered user = users.register();
    for (int i = 0; i < 4; i++) {
      users.login(user.email(), WRONG);
    }

    clock.advance(Duration.ofMillis(1500));
    assertTooMany(users.login(user.email(), WRONG), "1");

    // No instante exato em que o atraso acaba a tentativa já passa.
    clock.advance(Duration.ofMillis(500));
    assertThat(users.login(user.email(), WRONG).getStatus()).isEqualTo(401);
    assertThat(failuresOf(user.email())).contains(5);
    assertTooMany(users.login(user.email(), WRONG), "4");

    clock.advance(Duration.ofSeconds(4));
    assertThat(users.login(user.email(), PASSWORD).getStatus()).isEqualTo(200);
  }

  @Test
  void successDeletesTheRowSoTheCountStartsOver() throws Exception {
    TestUsers.Registered user = users.register();
    for (int i = 0; i < 3; i++) {
      users.login(user.email(), WRONG);
    }

    assertThat(users.login(user.email(), PASSWORD).getStatus()).isEqualTo(200);

    assertThat(failuresOf(user.email())).isEmpty();
    // Se não zerasse, a 4ª falha cairia em 429 antes da conta.
    for (int i = 0; i < 4; i++) {
      assertThat(users.login(user.email(), WRONG).getStatus()).isEqualTo(401);
    }
  }

  static Stream<Arguments> delayCeiling() {
    return Stream.of(
        Arguments.of(4, "2"),
        Arguments.of(5, "4"),
        Arguments.of(6, "8"),
        Arguments.of(12, "512"),
        // 2^10 = 1024 s passaria do teto de 15 min.
        Arguments.of(13, "900"),
        // Sem limitar o expoente antes da conta, 2^67 estouraria.
        Arguments.of(70, "900"),
        Arguments.of(2_000_000_000, "900"));
  }

  @ParameterizedTest(name = "{0} falhas -> {1} s")
  @MethodSource("delayCeiling")
  void delayGrowsByPowersOfTwoUpToFifteenMinutes(int failures, String retryAfter) throws Exception {
    String email = TestUsers.uniqueEmail();
    seedAttempts(email, failures);

    assertTooMany(users.login(email, WRONG), retryAfter);
  }

  @Test
  void ceilingReleasesExactlyFifteenMinutesAfterTheLastFailure() throws Exception {
    String email = TestUsers.uniqueEmail();
    seedAttempts(email, 50);

    clock.advance(Duration.ofSeconds(899));
    assertTooMany(users.login(email, WRONG), "1");
    clock.advance(Duration.ofSeconds(1));
    assertThat(users.login(email, WRONG).getStatus()).isEqualTo(401);
  }

  private record Seen(int status, String body, String retryAfter, List<String> headerNames) {}

  private List<Seen> observe(String email) throws Exception {
    clock.freeze(Instant.parse("2026-03-10T12:00:00Z"));
    List<Seen> seen = new ArrayList<>();
    long[] stepsInSeconds = {0, 0, 0, 0, 0, 1, 1, 0, 2, 0, 3, 0};
    for (long step : stepsInSeconds) {
      clock.advance(Duration.ofSeconds(step));
      MockHttpServletResponse response = users.login(email, WRONG);
      seen.add(
          new Seen(
              response.getStatus(),
              response.getContentAsString(StandardCharsets.UTF_8),
              response.getHeader("Retry-After"),
              response.getHeaderNames().stream().sorted().toList()));
    }
    return seen;
  }

  @Test
  void unknownEmailBehavesExactlyLikeAnExistingOne() throws Exception {
    TestUsers.Registered user = users.register();
    String unknown = TestUsers.uniqueEmail();

    List<Seen> withAccount = observe(user.email());
    List<Seen> withoutAccount = observe(unknown);

    assertThat(withoutAccount).isEqualTo(withAccount);
    assertThat(withAccount).extracting(Seen::status).contains(429);
    assertThat(failuresOf(unknown)).isPresent();
  }

  @Test
  void caseAndSpacesFallOnTheSameKey() throws Exception {
    TestUsers.Registered user = users.register();
    String email = user.email();

    for (String typed :
        List.of(
            email, email.toUpperCase(Locale.ROOT), " " + email + " ", "T" + email.substring(1))) {
      assertThat(users.login(typed, WRONG).getStatus()).isEqualTo(401);
    }

    assertThat(users.login(email.toUpperCase(Locale.ROOT), WRONG).getStatus()).isEqualTo(429);
    assertThat(failuresOf(email)).contains(4);
    assertThat(
            jdbc.sql("SELECT count(*) FROM tentativa_login WHERE lower(email) = :email")
                .param("email", email)
                .query(Integer.class)
                .single())
        .isEqualTo(1);
  }

  @Test
  void logsTheIncrementAndTheRefusalWithTheEmailMaskedAndNoSecrets(CapturedOutput output)
      throws Exception {
    TestUsers.Registered user = users.register();

    for (int i = 0; i < 5; i++) {
      users.login(user.email(), WRONG);
    }

    assertThat(output.getAll())
        .contains("login recusado: muitas tentativas")
        .contains("t***@exemplo.test")
        .contains("falhas=4")
        .contains("retry_after=2")
        .contains("127.0.0.1")
        .doesNotContain(user.email())
        .doesNotContain(WRONG)
        .doesNotContain(PASSWORD);
  }

  // Reserva atômica: sem ela, todas as threads passam pela checagem antes de qualquer falha ser
  // gravada e o limite de 4 verificações vale por lote, não por tentativa.
  @ParameterizedTest(name = "{0}")
  @MethodSource("concurrentCases")
  void concurrentAttemptsAreCountedOneByOne(String name, boolean accountExists) throws Exception {
    String email = accountExists ? users.register().email() : TestUsers.uniqueEmail();
    int attempts = 20;

    ExecutorService pool = Executors.newFixedThreadPool(attempts);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Integer>> results = new ArrayList<>();
    for (int i = 0; i < attempts; i++) {
      results.add(
          pool.submit(
              () -> {
                start.await();
                return users.login(email, WRONG).getStatus();
              }));
    }
    start.countDown();
    List<Integer> statuses = new ArrayList<>();
    for (Future<Integer> result : results) {
      statuses.add(result.get());
    }
    pool.shutdown();

    // 401 só sai depois de verificar a senha: é a contagem de verificações.
    long verified = statuses.stream().filter(s -> s == 401).count();
    long refused = statuses.stream().filter(s -> s == 429).count();
    assertThat(verified).as("%s", statuses).isEqualTo(4);
    assertThat(verified + refused).isEqualTo(attempts);
    assertThat(failuresOf(email)).contains(4);
  }

  static Stream<Arguments> concurrentCases() {
    return Stream.of(
        Arguments.of("e-mail existente", true), Arguments.of("e-mail inexistente", false));
  }
}
