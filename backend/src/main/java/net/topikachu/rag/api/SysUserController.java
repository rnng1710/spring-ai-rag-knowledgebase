package net.topikachu.rag.api;

import net.topikachu.rag.common.AjaxResult;
import net.topikachu.rag.entity.SysUser;
import net.topikachu.rag.service.SysUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/users")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class SysUserController {

    private final SysUserService sysUserService;

    @GetMapping
    public AjaxResult list() {
        return AjaxResult.success(sysUserService.findAll());
    }

    @PostMapping
    public AjaxResult create(@RequestBody SysUser user) {
        if (!StringUtils.hasText(user.getUsername()) || !StringUtils.hasText(user.getPassword())) {
            return AjaxResult.error(400, "Username and Password are required");
        }
        try {
            sysUserService.createUser(user);
            return AjaxResult.success("User created successfully");
        } catch (Exception e) {
            return AjaxResult.error(400, e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public AjaxResult delete(@PathVariable String id) {
        sysUserService.deleteUser(id);
        return AjaxResult.success("User deleted successfully");
    }

    @PutMapping("/{id}/password")
    public AjaxResult resetPassword(@PathVariable String id, @RequestBody Map<String, String> body) {
        String newPassword = body.get("password");
        if (!StringUtils.hasText(newPassword)) {
            return AjaxResult.error(400, "New password is required");
        }
        sysUserService.resetPassword(id, newPassword);
        return AjaxResult.success("Password reset successfully");
    }

    @PutMapping("/{id}/role")
    public AjaxResult updateRole(@PathVariable String id, @RequestBody Map<String, String> body) {
        String role = body.get("role");
        if (!StringUtils.hasText(role)) {
            return AjaxResult.error(400, "Role is required");
        }
        sysUserService.updateUserRole(id, role);
        return AjaxResult.success("Role updated successfully");
    }
}
