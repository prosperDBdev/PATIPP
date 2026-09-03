package com.patipp.common.jpa;

import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

/**
 * Base for entities whose primary key is assigned by the application rather than generated
 * by the database.
 *
 * <p>Every id in this system is a UUIDv7 minted in Java, which quietly breaks Spring Data's
 * default idea of newness. {@code SimpleJpaRepository.save()} decides between
 * {@code persist()} and {@code merge()} by asking whether the id is null; with an assigned
 * id it is never null, so every save took the {@code merge()} path.
 *
 * <p>That is wrong in two ways. It issues a redundant SELECT before every insert, and - far
 * worse - {@code merge()} returns a <em>different</em> managed instance and leaves the one you
 * passed in detached. Code that saved an entity and then kept mutating the local variable was
 * mutating a detached object, and the changes vanished without any error at all.
 *
 * <p>Implementing {@link Persistable} moves the decision to an explicit flag: new until the
 * row has actually been persisted or loaded.
 */
@MappedSuperclass
public abstract class AssignedIdEntity<T> implements Persistable<T> {

    @Transient
    private boolean persisted;

    @Override
    @Transient
    public boolean isNew() {
        return !persisted;
    }

    @PostPersist
    @PostLoad
    void markPersisted() {
        this.persisted = true;
    }
}
