package net.topikachu.rag.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import net.topikachu.rag.entity.SysUser;
import net.topikachu.rag.mapper.SysUserMapper;
import net.topikachu.rag.service.SysUserService;
import net.topikachu.rag.service.TokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class SysUserServiceImpl implements SysUserService {

    private final SysUserMapper sysUserMapper;

    private final PasswordEncoder passwordEncoder;

    private final TokenService tokenService;

    @Override
    public void register(String username, String password) {
        // Check if user exists
        SysUser exist = findByUsername(username);
        if (exist != null) {
            throw new RuntimeException("User already exists");
        }

        SysUser user = new SysUser();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(password));
        user.setRole("USER"); // Default role
        user.setEnabled(true);

        sysUserMapper.insert(user);
    }

    @Override
    public Map<String, String> login(String username, String password) {
        SysUser user = findByUsername(username);
        if (user == null) {
            throw new RuntimeException("Invalid username or password"); // Obscure error
        }

        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new RuntimeException("Invalid username or password");
        }

        if (!Boolean.TRUE.equals(user.getEnabled())) {
            throw new RuntimeException("Account is disabled");
        }

        // Generate tokens
        return tokenService.generateTokens(user);
    }

    @Override
    public void changePassword(String username, String oldPassword, String newPassword) {
        SysUser user = findByUsername(username);
        if (user == null) {
            throw new RuntimeException("User not found");
        }

        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
            throw new RuntimeException("Invalid old password");
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        sysUserMapper.updateById(user);
    }

    @Override
    public java.util.List<SysUser> findAll() {
        return sysUserMapper.selectList(null);
    }

    @Override
    public void createUser(SysUser user) {
        if (findByUsername(user.getUsername()) != null) {
            throw new RuntimeException("User already exists");
        }
        user.setPassword(passwordEncoder.encode(user.getPassword()));
        // If role is missing, default to USER
        if (user.getRole() == null) {
            user.setRole("USER");
        }
        user.setEnabled(true);
        sysUserMapper.insert(user);
    }

    @Override
    public void deleteUser(String id) {
        sysUserMapper.deleteById(id);
    }

    @Override
    public void resetPassword(String id, String newPassword) {
        SysUser user = sysUserMapper.selectById(id);
        if (user != null) {
            user.setPassword(passwordEncoder.encode(newPassword));
            sysUserMapper.updateById(user);
        }
    }

    @Override
    public void updateUserRole(String id, String role) {
        SysUser user = sysUserMapper.selectById(id);
        if (user != null) {
            user.setRole(role);
            sysUserMapper.updateById(user);
        }
    }

    @Override
    public void updateUserDepartment(String id, String deptId, String deptName) {
        SysUser user = sysUserMapper.selectById(id);
        if (user != null) {
            user.setDeptId(deptId);
            user.setDeptName(deptName);
            sysUserMapper.updateById(user);
        }
    }

    @Override
    public void updateDefaultSpace(String id, String defaultSpaceCode) {
        SysUser user = sysUserMapper.selectById(id);
        if (user != null) {
            user.setDefaultSpaceCode(defaultSpaceCode);
            sysUserMapper.updateById(user);
        }
    }

    @Override
    public SysUser findByUsername(String username) {
        return sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username));
    }
}
