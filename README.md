# dot-transfer

A money-transfer service for the DotLabs take-home: transfers between accounts, a filterable
transaction history, a nightly commission assessment, and daily transaction summaries.

Spring Boot 4.1.1 · Java 21 · PostgreSQL · Maven.

---

## Running it

```bash
docker compose up -d          # Postgres 18 on localhost:5437
./mvnw spring-boot:run        # http://localhost:8080
```

The `dev` profile (active by default) seeds four accounts on first start, chosen to cover the cases
worth trying:

| Account | Holder | Balance | State | Useful for |
|---|---|---|---|---|
| `1000000001` | Ada Obi | 500,000.00 | ACTIVE | ordinary transfers |
| `1000000002` | Bola Ade | 250,000.00 | ACTIVE | the receiving side |
| `1000000003` | Ada Obi | 1,000.00 | ACTIVE | `INSUFFICIENT_FUND` |
| `1000000004` | Bola Ade | 75,000.00 | FROZEN | inactive-account rejection |

API docs: **http://localhost:8080/docs** (Scalar) · health: `/actuator/health` · OpenAPI JSON:
`/v3/api-docs`. The bare root redirects to the docs, since the service has no home page of its own.

Set `APP_ENV=prod` to disable the seeder. All configuration is env-var driven — `DB_URL`,
`DB_USERNAME`, `DB_PASSWORD`, `DB_POOL_SIZE`, `SERVER_PORT`, `BUSINESS_ZONE`, `COMMISSION_CRON`,
`SUMMARY_CRON`.

---

## The money rules

```
fee        = min(amount × 0.5%, 100)     the cap binds from amount ≥ 20,000
billed     = amount + fee                the sender bears the fee
commission = fee × 20%                   successful transactions only, assigned nightly
```

So a transfer of 50,000 debits 50,100.00 from the sender and credits 50,000.00 to the recipient;
its fee is 100.00 (capped, not 250.00) and it later earns 20.00 of commission.

Two consequences worth stating explicitly:

- **The balance check is against `billed`, not `amount`.** Because the sender pays the fee, an
  account holding exactly the transfer amount cannot afford the transfer.
- **A rejected transfer still records its fee.** No money moves, but the row shows the threshold it
  was tested against — which is what explains the rejection. Summaries therefore total fees over
  successful transactions only.

Rates live in `dot.fee.*`; none of these numbers is a literal in code.

---

## Endpoints

### `POST /api/v1/transfers`

```bash
curl -X POST localhost:8080/api/v1/transfers \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: order-4471' \
  -d '{"sourceAccountNumber":"1000000001",
       "destinationAccountNumber":"1000000002",
       "amount":50000.00,
       "description":"Rent"}'
```

Always returns **201** — a transaction record is created whether or not the money moved. The
outcome is `data.status`: `SUCCESSFUL`, `INSUFFICIENT_FUND` or `FAILED`. A rejected transfer comes
back as 201 with `status: false` and the reason in `message`.

Genuine request errors are 4xx and create nothing: unknown account (404), inactive account (409),
same account on both sides or a malformed body (400).

`Idempotency-Key` is optional and makes retries safe — repeating a request with the same key
returns the original transaction rather than transferring again.

### `GET /api/v1/transactions`

```bash
curl "localhost:8080/api/v1/transactions?status=SUCCESSFUL&accountNumber=1000000002\
&from=2026-09-01&to=2026-09-02&page=0&size=20"
```

Paginated, newest first. All filters optional and combinable. `accountNumber` matches **both sides**
of a transfer — money the account sent as well as money it received. `from`/`to` are inclusive
business dates (`yyyy-MM-dd`).

### `GET /api/v1/transactions/summary`

```bash
curl "localhost:8080/api/v1/transactions/summary?date=2026-09-02"
```

Totals for one business day, present or past. A closed day is served from the snapshot the nightly
job stored (`fromSnapshot: true`); a day with no snapshot — the current one, or a past one the job
has not covered — is computed live. The current day is additionally flagged `provisional`, because
it can still change.

This is a pure read and never writes a snapshot. An earlier version cached its result here, which
meant two concurrent reads of the same un-snapshotted day both tried to insert it and the unique
constraint failed one of them — a plain GET returning a conflict. Regeneration now belongs solely to
`POST /api/v1/admin/jobs/summary`.

### `GET /api/v1/transactions/{reference}`

A single transaction.

### `POST /api/v1/admin/jobs/{commission,summary}?date=`

Manual triggers for the two scheduled jobs, so their behaviour can be seen without waiting for
midnight. `date` is optional: omitting it on `/commission` clears the whole backlog exactly as the
nightly job does, and omitting it on `/summary` snapshots yesterday. Both are safe to re-run —
commission finds nothing outstanding, and a summary replaces its day's snapshot rather than
duplicating it.

Unsecured — authentication is outside this exercise's scope, and they are grouped under `/admin` so
restricting them is one routing rule.

---

## Scheduled jobs

| Job | Default time | Lock name | What it does |
|---|---|---|---|
| Commission | 00:05 | `commission-assessment` | Assigns commission to every closed day still holding unassessed transactions |
| Daily summary | 00:30 | `daily-summary` | Snapshots the previous day's totals |

The 25-minute gap is deliberate: the summary reads commission figures, so commission must finish
first. Both times are configurable.

**The commission job clears a backlog, not just yesterday.** A job that only ever looked at the
previous day would leave any day missed during an outage unassessed forever — silently reporting
zero commission for it, with nothing to notice or repair the gap. Instead it asks which closed days
still hold unassessed transactions and works through them, most recent first, capped at
`dot.jobs.max-backfill-days` (30) so one run cannot take on unbounded work after a long outage.
A normal night finds exactly one day and behaves accordingly.

The commission job marks **every** unassessed transaction, not only the successful ones — successful
ones get `commissionWorthy: true` and a commission, everything else gets `false` and zero. That
keeps `null` meaning exactly one thing ("not yet assessed"), which is the sentinel the job claims
work with, and makes a re-run a genuine no-op.

---

## Running multiple instances

The brief's constraint that services run as several pods drives most of the design here.

**Scheduled jobs fire once, not once per pod.** Every instance holds the same cron triggers, so all
of them wake together. [ShedLock](https://github.com/lukas-krecan/ShedLock) turns the shared database
into the arbiter — the first instance to insert its row into `shedlock` runs the job and the rest
skip. Lock timing is read from the *database* clock (`usingDbTime()`), not each pod's, so clock drift
between pods cannot cause two of them to believe a lock has expired. `lockAtMostFor` is the crash
guard: if the holder dies without releasing, the lock expires and the next run proceeds. Each job
also calls `LockAssert.assertLocked()`, which fails loudly if the lock was never applied at all —
a misconfigured provider would otherwise be invisible until the numbers were already wrong.

**Concurrent transfers cannot lose a debit.** Both accounts are read under
`SELECT ... FOR UPDATE` before either balance is touched, and the locks are always taken in
ascending account-number order — never in the direction of the transfer — so `A→B` and a
simultaneous `B→A` queue behind each other instead of deadlocking. `@Version` on the entity is a
second line of defence behind that.

**Retries are safe across instances.** `idempotency_key` carries a unique constraint. If two pods
handle the same retried request and both miss the lookup, exactly one insert survives; the loser
catches the violation, reads back the winner's row, and returns it rather than debiting twice.

**Every instance agrees on where a day starts.** `transaction_date` is stamped once at creation in a
configured business zone (`BUSINESS_ZONE`) and stored, rather than derived per-query from a UTC
instant. Two pods in different timezones cannot disagree about which day a transaction belongs to,
and the summary and date-range queries become indexed single-column lookups instead of timezone
conversions no index can serve.

**The application holds no state.** No sessions, no in-memory caches, no local files. Graceful
shutdown (30s) lets an in-flight transfer finish committing during a rolling restart rather than
being severed between the debit and the credit. Liveness and readiness probes are exposed separately
so a wedged pod is restarted while a merely busy one is only taken out of the load balancer. The
connection pool is sized *per instance* (`DB_POOL_SIZE`, default 10) — N pods means N times that
many connections at the database.

**Schema changes are the one gap, and it is deliberate** — see below.

---

## Known gaps

Two things are deferred on purpose rather than overlooked.

**Schema management.** `ddl-auto: update` is a development stopgap and is marked `TODO(flyway)`
in `application.yaml`. It is unsafe when several instances start at once, because they race to alter
the same schema. Flyway replaces it (with `ddl-auto: validate`) once the entity design is settled;
Flyway takes a migration lock, which is what actually makes concurrent startup safe. `schema.sql`
exists only to create ShedLock's own table, which Hibernate cannot manage because it is not an
entity, and folds into the Flyway baseline at the same time.

**Automated tests.** Not yet written. The intended coverage is unit tests for the fee and commission
math (including the cap boundary at exactly 20,000), `@DataJpaTest` for the specification filters and
the idempotency constraint, `@WebMvcTest` for validation and the error envelope, and a Testcontainers
integration test firing concurrent transfers at one account to assert no overdraw.

In the meantime the behaviour above was verified by hand against a live Postgres. The concurrency
check: account `1000000003` holding 1,000.00, hit with 30 simultaneous transfers of 100.00
(billed 100.50 each). Exactly 9 succeeded — 9 × 100.50 = 904.50, and a 10th would have needed
1,005.00 — 21 were rejected as `INSUFFICIENT_FUND`, the closing balance was exactly 95.50, and no
account anywhere went negative.
