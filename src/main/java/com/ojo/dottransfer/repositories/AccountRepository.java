package com.ojo.dottransfer.repositories;

import com.ojo.dottransfer.models.entities.Account;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface AccountRepository extends JpaRepository<Account, UUID> {

    Optional<Account> findByAccountNumber(String accountNumber);

    /**
     * Reads an account under a row-level write lock ({@code SELECT ... FOR UPDATE}).
     *
     * <p>This is what makes concurrent transfers correct across instances. Two pods debiting the
     * same account would otherwise both read the old balance and both write their own result, and
     * one debit would vanish. The lock serialises them at the database, which is the only place all
     * the instances actually meet.
     *
     * <p>Callers must acquire locks in a deterministic order - see
     * {@code TransferExecutor} - or two opposing transfers will deadlock.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.accountNumber = :accountNumber")
    Optional<Account> findByAccountNumberForUpdate(@Param("accountNumber") String accountNumber);
}
