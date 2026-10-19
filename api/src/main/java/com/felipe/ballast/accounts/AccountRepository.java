package com.felipe.ballast.accounts;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

interface AccountRepository extends JpaRepository<Account, UUID> {

  // Um UPDATE só, filtrado por dono e id: devolve 0 para conta alheia ou inexistente, sem
  // ler a linha antes.
  @Transactional
  @Modifying
  @Query("update Account a set a.archived = :archived where a.id = :id and a.userId = :userId")
  int updateArchived(
      @Param("userId") UUID userId, @Param("id") UUID id, @Param("archived") boolean archived);
}
