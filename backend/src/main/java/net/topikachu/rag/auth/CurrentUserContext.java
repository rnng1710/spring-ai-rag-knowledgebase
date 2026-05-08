package net.topikachu.rag.auth;

public record CurrentUserContext(
        String userId,
        String username,
        String role,
        String deptId,
        String deptName,
        boolean isAdmin
) {
}
