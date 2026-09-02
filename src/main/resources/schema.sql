-- TODO(flyway): temporary. ShedLock's table cannot be created by Hibernate because it is not a
-- JPA entity, and Flyway is deferred until the entity design is signed off. This file is deleted
-- and this DDL folded into V1__baseline.sql at that point.
CREATE TABLE IF NOT EXISTS shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
