package net.topikachu.rag.api;

import lombok.RequiredArgsConstructor;
import net.topikachu.rag.common.AjaxResult;
import net.topikachu.rag.entity.SysUser;
import net.topikachu.rag.service.SysUserService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/users")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class SysUserController {

    private final SysUserService sysUserService;

    @GetMapping
    public Mono<AjaxResult> list() {
        return Mono.fromCallable(sysUserService::findAll)
                .subscribeOn(Schedulers.boundedElastic())
                .map(AjaxResult::success);
    }

    @PostMapping
    public Mono<AjaxResult> create(@RequestBody SysUser user) {
        if (!StringUtils.hasText(user.getUsername()) || !StringUtils.hasText(user.getPassword())) {
            return Mono.just(AjaxResult.error(400, "Username and Password are required"));
        }
        return Mono.fromRunnable(() -> sysUserService.createUser(user))
                .subscribeOn(Schedulers.boundedElastic())
                .thenReturn(AjaxResult.success("User created successfully", null))
                .onErrorResume(e -> Mono.just(AjaxResult.error(400, e.getMessage())));
    }

    @DeleteMapping("/{id}")
    public Mono<AjaxResult> delete(@PathVariable String id) {
        return Mono.fromRunnable(() -> sysUserService.deleteUser(id))
                .subscribeOn(Schedulers.boundedElastic())
                .thenReturn(AjaxResult.success("User deleted successfully", null));
    }

    @PutMapping("/{id}/password")
    public Mono<AjaxResult> resetPassword(@PathVariable String id, @RequestBody Map<String, String> body) {
        String newPassword = body.get("password");
        if (!StringUtils.hasText(newPassword)) {
            return Mono.just(AjaxResult.error(400, "New password is required"));
        }
        return Mono.fromRunnable(() -> sysUserService.resetPassword(id, newPassword))
                .subscribeOn(Schedulers.boundedElastic())
                .thenReturn(AjaxResult.success("Password reset successfully", null));
    }

    @PutMapping("/{id}/role")
    public Mono<AjaxResult> updateRole(@PathVariable String id, @RequestBody Map<String, String> body) {
        String role = body.get("role");
        if (!StringUtils.hasText(role)) {
            return Mono.just(AjaxResult.error(400, "Role is required"));
        }
        return Mono.fromRunnable(() -> sysUserService.updateUserRole(id, role))
                .subscribeOn(Schedulers.boundedElastic())
                .thenReturn(AjaxResult.success("Role updated successfully", null));
    }

    @PutMapping("/{id}/department")
    public Mono<AjaxResult> updateDepartment(@PathVariable String id, @RequestBody Map<String, String> body) {
        return Mono.fromRunnable(() -> sysUserService.updateUserDepartment(id, body.get("deptId"), body.get("deptName")))
                .subscribeOn(Schedulers.boundedElastic())
                .thenReturn(AjaxResult.success("Department updated successfully", null));
    }

    @PutMapping("/{id}/default-space")
    public Mono<AjaxResult> updateDefaultSpace(@PathVariable String id, @RequestBody Map<String, String> body) {
        return Mono.fromRunnable(() -> sysUserService.updateDefaultSpace(id, body.get("defaultSpaceCode")))
                .subscribeOn(Schedulers.boundedElastic())
                .thenReturn(AjaxResult.success("Default space updated successfully", null));
    }
}
