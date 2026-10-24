package com.felipe.ballast.recurrences;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

// Sem Spring nem banco: um serviço de mentira devolve os templates em lotes e falha em um deles.
class RecurrenceJobBatchTest {

  private static UUID id(long n) {
    return new UUID(0L, n);
  }

  private static final class FakeService extends RecurrenceService {

    final List<RecurrenceQueries.TemplateRef> all = new ArrayList<>();
    final List<UUID> materialized = new ArrayList<>();
    final List<UUID> afterIds = new ArrayList<>();
    UUID failingTemplate;

    FakeService() {
      super(null, null, null, null);
    }

    @Override
    List<RecurrenceQueries.TemplateRef> activeTemplates(UUID afterId, int limit) {
      afterIds.add(afterId);
      return all.stream()
          .filter(ref -> ref.templateId().compareTo(afterId) > 0)
          .limit(limit)
          .toList();
    }

    @Override
    int materialize(UUID userId, UUID templateId) {
      materialized.add(templateId);
      if (templateId.equals(failingTemplate)) {
        throw new IllegalStateException("falha de teste");
      }
      return 1;
    }
  }

  private static FakeService serviceWith(int templates) {
    FakeService service = new FakeService();
    for (long n = 1; n <= templates; n++) {
      service.all.add(new RecurrenceQueries.TemplateRef(id(100 + n), id(n)));
    }
    return service;
  }

  @Test
  void walksEveryTemplateInBatchesByPrimaryKey() {
    FakeService service = serviceWith(5);

    new RecurrenceJob(service, 2).run();

    assertThat(service.materialized).containsExactly(id(1), id(2), id(3), id(4), id(5));
    assertThat(service.afterIds).containsExactly(new UUID(0, 0), id(2), id(4), id(5));
  }

  @Test
  void aFailureInOneTemplateDoesNotStopTheOthers() {
    FakeService service = serviceWith(5);
    service.failingTemplate = id(3);

    new RecurrenceJob(service, 2).run();

    assertThat(service.materialized).containsExactly(id(1), id(2), id(3), id(4), id(5));
  }

  @Test
  void withNoTemplatesItDoesNothing() {
    FakeService service = serviceWith(0);

    new RecurrenceJob(service, 2).run();

    assertThat(service.materialized).isEmpty();
  }
}
