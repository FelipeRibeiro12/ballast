package com.felipe.ballast.entries;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

interface TransferRepository extends JpaRepository<Transfer, UUID> {

  @Transactional
  @Modifying
  @Query("delete from Transfer t where t.id = :id and t.userId = :userId")
  int deleteOwned(@Param("userId") UUID userId, @Param("id") UUID id);
}
