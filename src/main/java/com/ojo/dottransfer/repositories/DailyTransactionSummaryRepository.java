package com.ojo.dottransfer.repositories;

import com.ojo.dottransfer.models.entities.DailyTransactionSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DailyTransactionSummaryRepository extends JpaRepository<DailyTransactionSummary, UUID> {

    Optional<DailyTransactionSummary> findBySummaryDate(LocalDate summaryDate);
}
