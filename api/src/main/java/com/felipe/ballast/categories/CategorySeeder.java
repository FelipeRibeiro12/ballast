package com.felipe.ballast.categories;

import com.felipe.ballast.identity.UserRegistered;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

// Roda dentro da transação do cadastro (evento síncrono, sem @TransactionalEventListener): se
// falhar, o usuário também não é criado. O identity não conhece este pacote.
@Component
class CategorySeeder {

  private final CategoryRepository categories;

  CategorySeeder(CategoryRepository categories) {
    this.categories = categories;
  }

  @EventListener
  void seed(UserRegistered event) {
    categories.saveAllAndFlush(
        CategorySeeds.ALL.stream()
            .map(seed -> new Category(event.userId(), seed.name(), seed.type()))
            .toList());
  }
}
