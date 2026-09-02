package com.ojo.dottransfer.models;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.proxy.HibernateProxy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

/**
 * Shared identity, optimistic-locking and auditing columns for every entity.
 *
 * <p>{@code equals}/{@code hashCode} are written by hand rather than generated, because the
 * generated form is wrong for JPA in two ways. A hash derived from the id changes the moment the id
 * is assigned, so an entity added to a hash-based collection before persisting is lost inside it;
 * this returns a constant instead. And a lazily-loaded association is a Hibernate proxy whose
 * {@code getClass()} is a generated subclass, so a naive class comparison declares an entity unequal
 * to its own proxy; the persistent class is unwrapped first.
 */
@Getter
@Setter
@MappedSuperclass
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Version
    @Column(name = "version")
    private Long version;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private Instant updatedAt;

    @Override
    public final boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (other == null || !persistentClass(this).equals(persistentClass(other))) {
            return false;
        }
        // Two unsaved entities are equal only if they are the same object, caught above.
        return id != null && id.equals(((BaseEntity) other).getId());
    }

    @Override
    public final int hashCode() {
        return persistentClass(this).hashCode();
    }

    private static Class<?> persistentClass(Object entity) {
        return entity instanceof HibernateProxy proxy
                ? proxy.getHibernateLazyInitializer().getPersistentClass()
                : entity.getClass();
    }
}
