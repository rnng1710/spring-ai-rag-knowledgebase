package net.topikachu.rag.auth;

import lombok.RequiredArgsConstructor;
import net.topikachu.rag.entity.SysUser;
import net.topikachu.rag.service.SysUserService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class CurrentUserContextService {

    private final SysUserService sysUserService;

    public CurrentUserContext resolveByUsername(String username) {
        if (!StringUtils.hasText(username)) {
            throw new IllegalArgumentException("Username is required");
        }
        SysUser user = sysUserService.findByUsername(username);
        if (user == null) {
            throw new IllegalArgumentException("User not found: " + username);
        }
        String role = StringUtils.hasText(user.getRole()) ? user.getRole().trim() : "USER";
        return new CurrentUserContext(
                user.getId(),
                user.getUsername(),
                role,
                user.getDeptId(),
                user.getDeptName(),
                user.getDefaultSpaceCode(),
                "ADMIN".equalsIgnoreCase(role));
    }
}
