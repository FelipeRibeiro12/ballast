package com.felipe.ballast.entries;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

interface EntryRepository extends JpaRepository<Entry, UUID> {

  // Um UPDATE só, filtrado por dono e id: devolve 0 para lançamento alheio ou inexistente, sem
  // ler a linha antes. A data enviada é a competência; a caixa vem calculada (cartão, ADR-031).
  @Transactional
  @Modifying
  @Query(
      """
      update Entry e
      set e.type = :type, e.title = :title, e.description = :description,
          e.categoryId = :categoryId, e.accountId = :accountId, e.amount = :amount,
          e.competenceDate = :date, e.cashDate = :cashDate
      where e.id = :id and e.userId = :userId
      """)
  int replace(
      @Param("userId") UUID userId,
      @Param("id") UUID id,
      @Param("type") String type,
      @Param("title") String title,
      @Param("description") String description,
      @Param("categoryId") UUID categoryId,
      @Param("accountId") UUID accountId,
      @Param("amount") BigDecimal amount,
      @Param("date") LocalDate date,
      @Param("cashDate") LocalDate cashDate);

  @Transactional
  @Modifying
  @Query("delete from Entry e where e.id = :id and e.userId = :userId")
  int deleteOwned(@Param("userId") UUID userId, @Param("id") UUID id);
}
