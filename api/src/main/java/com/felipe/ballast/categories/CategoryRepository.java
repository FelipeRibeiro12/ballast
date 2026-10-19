package com.felipe.ballast.categories;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface CategoryRepository extends JpaRepository<Category, UUID> {

  // Tipo antes do nome; id desempata nomes que só diferem na caixa.
  @Query("select c from Category c where c.userId = :userId order by c.type, lower(c.name), c.id")
  List<Category> findAllByUser(@Param("userId") UUID userId);

  @Query(
      "select c from Category c where c.userId = :userId and c.type = :type"
          + " order by lower(c.name), c.id")
  List<Category> findAllByUserAndType(@Param("userId") UUID userId, @Param("type") String type);
}
