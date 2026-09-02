-- dot-transfer baseline schema.
--
-- Hand-written rather than dumped, so every constraint carries a name that says what it protects
-- instead of a generated one like `uk6kplolsdtr3slnvx97xsy2kc8`. The names matter: a violation
-- surfaces in logs and error handling by constraint name, and readable ones make the cause obvious.
--
-- Money is numeric(19,2) throughout - naira to kobo precision. Instants are `timestamp(6) with
-- time zone` and always stored UTC; a business day is a plain `date` fixed at creation in the
-- configured business zone, never derived from an instant at query time.

-- ---------------------------------------------------------------------------------------------
-- customers
-- ---------------------------------------------------------------------------------------------
CREATE TABLE customers (
    id           uuid                        NOT NULL,
    version      bigint                      NOT NULL,
    created_at   timestamp(6) with time zone NOT NULL,
    updated_at   timestamp(6) with time zone,
    first_name   varchar(100)                NOT NULL,
    last_name    varchar(100)                NOT NULL,
    email        varchar(255)                NOT NULL,
    phone_number varchar(15)                 NOT NULL,

    CONSTRAINT pk_customers PRIMARY KEY (id),
    CONSTRAINT uk_customers_email UNIQUE (email),
    CONSTRAINT uk_customers_phone_number UNIQUE (phone_number)
);

-- ---------------------------------------------------------------------------------------------
-- accounts
-- ---------------------------------------------------------------------------------------------
CREATE TABLE accounts (
    id             uuid                        NOT NULL,
    version        bigint                      NOT NULL,
    created_at     timestamp(6) with time zone NOT NULL,
    updated_at     timestamp(6) with time zone,
    customer_id    uuid                        NOT NULL,
    account_number varchar(20)                 NOT NULL,
    account_type   varchar(20)                 NOT NULL,
    status         varchar(20)                 NOT NULL,
    balance        numeric(19, 2)              NOT NULL,
    currency       varchar(3)                  NOT NULL,

    CONSTRAINT pk_accounts PRIMARY KEY (id),
    CONSTRAINT uk_accounts_account_number UNIQUE (account_number),
    CONSTRAINT fk_accounts_customer FOREIGN KEY (customer_id) REFERENCES customers (id),

    -- The enum domain is enforced by the database as well as the application. An out-of-range
    -- value can then only arrive through a code path that has already been rejected here.
    CONSTRAINT ck_accounts_type   CHECK (account_type IN ('SAVINGS', 'CURRENT')),
    CONSTRAINT ck_accounts_status CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED'))
);

-- Postgres does not index a foreign key column on its own; without this, every lookup of a
-- customer's accounts is a sequential scan.
CREATE INDEX idx_accounts_customer ON accounts (customer_id);

-- ---------------------------------------------------------------------------------------------
-- transactions
-- ---------------------------------------------------------------------------------------------
CREATE TABLE transactions (
    id                         uuid                        NOT NULL,
    version                    bigint                      NOT NULL,
    created_at                 timestamp(6) with time zone NOT NULL,
    updated_at                 timestamp(6) with time zone,

    transaction_reference      varchar(40)                 NOT NULL,
    idempotency_key            varchar(64),
    transaction_date           date                        NOT NULL,

    source_account_id          uuid                        NOT NULL,
    destination_account_id     uuid                        NOT NULL,
    -- Denormalised alongside the relations above: a ledger row must stay readable after an
    -- account is closed, and filtering history by account number should not need a join.
    source_account_number      varchar(20)                 NOT NULL,
    destination_account_number varchar(20)                 NOT NULL,

    amount                     numeric(19, 2)              NOT NULL,
    transaction_fee            numeric(19, 2)              NOT NULL,
    billed_amount              numeric(19, 2)              NOT NULL,
    currency                   varchar(3)                  NOT NULL,
    description                varchar(255),

    status                     varchar(30)                 NOT NULL,
    status_message             varchar(255),

    -- Nullable on purpose: null means "not yet assessed", the sentinel the nightly commission job
    -- claims work with. After a day has been assessed, none of its rows are left null.
    commission_worthy          boolean,
    commission                 numeric(19, 2),
    commission_computed_at     timestamp(6) with time zone,

    CONSTRAINT pk_transactions PRIMARY KEY (id),
    CONSTRAINT uk_transactions_reference UNIQUE (transaction_reference),

    -- The guarantee behind safe retries. Postgres permits many NULLs in a unique index, so
    -- requests that send no key are unaffected, while two instances handling the same retried
    -- request cannot both insert.
    CONSTRAINT uk_transactions_idempotency_key UNIQUE (idempotency_key),

    CONSTRAINT fk_transactions_source_account
        FOREIGN KEY (source_account_id) REFERENCES accounts (id),
    CONSTRAINT fk_transactions_destination_account
        FOREIGN KEY (destination_account_id) REFERENCES accounts (id),

    CONSTRAINT ck_transactions_status
        CHECK (status IN ('SUCCESSFUL', 'INSUFFICIENT_FUND', 'FAILED'))
);

-- The daily summary aggregate: one day, grouped by status.
CREATE INDEX idx_txn_date_status ON transactions (transaction_date, status);

-- History filtered by account number, which must match either side of a transfer.
CREATE INDEX idx_txn_source_account ON transactions (source_account_number);
CREATE INDEX idx_txn_destination_account ON transactions (destination_account_number);

-- The commission job's claim query: one day, not yet assessed.
CREATE INDEX idx_txn_date_commission ON transactions (transaction_date, commission_worthy);

-- ---------------------------------------------------------------------------------------------
-- daily_transaction_summaries
-- ---------------------------------------------------------------------------------------------
CREATE TABLE daily_transaction_summaries (
    id                      uuid                        NOT NULL,
    version                 bigint                      NOT NULL,
    created_at              timestamp(6) with time zone NOT NULL,
    updated_at              timestamp(6) with time zone,

    summary_date            date                        NOT NULL,
    total_count             bigint                      NOT NULL,
    successful_count        bigint                      NOT NULL,
    failed_count            bigint                      NOT NULL,
    insufficient_fund_count bigint                      NOT NULL,
    commission_worthy_count bigint                      NOT NULL,
    total_amount            numeric(19, 2)              NOT NULL,
    successful_amount       numeric(19, 2)              NOT NULL,
    total_fees              numeric(19, 2)              NOT NULL,
    total_commission        numeric(19, 2)              NOT NULL,
    generated_at            timestamp(6) with time zone NOT NULL,

    CONSTRAINT pk_daily_summaries PRIMARY KEY (id),

    -- One snapshot per day. This is what makes re-running the summary job an update rather than
    -- an accumulation of duplicates.
    CONSTRAINT uk_daily_summaries_date UNIQUE (summary_date)
);

-- ---------------------------------------------------------------------------------------------
-- shedlock
-- ---------------------------------------------------------------------------------------------
-- ShedLock's own table, owned by the library rather than by an entity - column names and types are
-- fixed by its JdbcTemplateLockProvider. This is how the scheduled jobs run exactly once across
-- however many instances are deployed: whichever one inserts its row here first runs the job.
CREATE TABLE shedlock (
    name       varchar(64)  NOT NULL,
    lock_until timestamp    NOT NULL,
    locked_at  timestamp    NOT NULL,
    locked_by  varchar(255) NOT NULL,

    CONSTRAINT pk_shedlock PRIMARY KEY (name)
);
