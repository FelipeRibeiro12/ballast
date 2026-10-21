package com.felipe.ballast.entries;

import static org.assertj.core.api.Assertions.assertThat;

import com.felipe.ballast.IntegrationTest;
import com.felipe.ballast.accounts.BenefitApi;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class BenefitTransferTest {

  private static final String REFUSED =
      "{\"erro\":\"conta de benefício não participa de transferência\"}";

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  private EntriesApi entries;
  private TransfersApi transfers;
  private EntriesApi.Actor actor;
  private String benefit;

  @BeforeEach
  void setUp() throws Exception {
    entries = new EntriesApi(mvc);
    transfers = new TransfersApi(mvc);
    actor = entries.newActor();
    benefit = new BenefitApi(mvc).createOk(actor.session(), "VR", List.of());
  }

  private long legsOf(UUID userId) {
    return jdbc.sql("SELECT count(*) FROM lancamento WHERE usuario_id = :id")
        .param("id", userId)
        .query(Long.class)
        .single();
  }

  @Test
  void aBenefitAsOriginIs400AndNothingIsSaved() throws Exception {
    MockHttpServletResponse response =
        transfers.create(actor.session(), TransfersApi.body(benefit, actor.accountId(), 1_000));

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(EntriesApi.text(response)).isEqualTo(REFUSED);
    assertThat(legsOf(actor.user().id())).isZero();
  }

  @Test
  void aBenefitAsDestinationIs400AndNothingIsSaved() throws Exception {
    MockHttpServletResponse response =
        transfers.create(actor.session(), TransfersApi.body(actor.accountId(), benefit, 1_000));

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(EntriesApi.text(response)).isEqualTo(REFUSED);
    assertThat(legsOf(actor.user().id())).isZero();
  }

  @Test
  void aBenefitOfAnotherUserStillAnswersAccountNotFound() throws Exception {
    EntriesApi.Actor other = entries.newActor();
    String theirs = new BenefitApi(mvc).createOk(other.session(), "VR de B", List.of());

    MockHttpServletResponse response =
        transfers.create(actor.session(), TransfersApi.body(actor.accountId(), theirs, 1_000));

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(EntriesApi.text(response)).isEqualTo("{\"erro\":\"conta não encontrada\"}");
  }
}
