package net.topikachu.rag.util;

import net.topikachu.rag.auth.CurrentUserContext;
import net.topikachu.rag.common.BaseEntity;

import java.time.LocalDateTime;

public final class BaseEntityUtil {

    private static final int INITIAL_VERSION = 0;

    private BaseEntityUtil() {
    }

    public static <T extends BaseEntity> T initCreateFields(T entity) {
        return initCreateFields(entity, null, null);
    }

    public static <T extends BaseEntity> T initCreateFields(T entity, CurrentUserContext currentUserContext) {
        if (currentUserContext == null) {
            return initCreateFields(entity);
        }
        return initCreateFields(entity, currentUserContext.userId(), currentUserContext.username());
    }

    public static <T extends BaseEntity> T initCreateFields(T entity, String createUserId, String createUserName) {
        if (entity == null) {
            throw new IllegalArgumentException("Entity is required");
        }
        entity.setCreateUserId(createUserId);
        entity.setCreateUserName(createUserName);
        entity.setCreateDate(LocalDateTime.now());
        entity.setVersion(INITIAL_VERSION);
        return entity;
    }
}
