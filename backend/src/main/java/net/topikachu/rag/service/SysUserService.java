package net.topikachu.rag.service;

import net.topikachu.rag.entity.SysUser;
import java.util.Map;

public interface SysUserService {

    /**
     * Register a new user
     */
    void register(String username, String password);

    /**
     * Login and return token map (accessToken, refreshToken)
     */
    Map<String, String> login(String username, String password);

    /**
     * Change password
     */
    void changePassword(String username, String oldPassword, String newPassword);

    /**
     * Find user by username
     */
    SysUser findByUsername(String username);

    // Admin Methods

    java.util.List<SysUser> findAll();

    void createUser(SysUser user);

    void deleteUser(String id);

    void resetPassword(String id, String newPassword);

    void updateUserRole(String id, String role);

    void updateUserDepartment(String id, String deptId, String deptName);
}
