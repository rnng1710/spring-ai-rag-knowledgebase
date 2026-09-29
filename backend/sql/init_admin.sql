-- 首次管理员初始化：先导入完整表结构，再在同一 MySQL 会话中设置：
-- SET @admin_username = 'admin';
-- SET @admin_password_hash = '<你自己密码的 BCrypt 哈希>';
-- SOURCE backend/sql/init_admin.sql;
-- 已有启用的管理员或同名用户时不写入，不覆盖账号或提升现有用户权限。

SET @admin_username = COALESCE(@admin_username, 'admin');
SET @admin_password_hash = COALESCE(@admin_password_hash, '');
SET @admin_input_valid = (
	CHAR_LENGTH(TRIM(@admin_username)) BETWEEN 1 AND 50
	AND REGEXP_LIKE(@admin_password_hash,
		'^[$]2[aby][$](0[4-9]|[12][0-9]|3[01])[$][./A-Za-z0-9]{53}$', 'c')
);

INSERT INTO sys_user (id, username, password, role, enabled, version, remark)
SELECT REPLACE(UUID(), '-', ''), @admin_username, @admin_password_hash,
	'ADMIN', 1, 0, 'Initial administrator'
WHERE @admin_input_valid
	AND NOT EXISTS (SELECT 1 FROM sys_user WHERE role = 'ADMIN' AND enabled = 1)
	AND NOT EXISTS (SELECT 1 FROM sys_user WHERE username = @admin_username);

SET @admin_created = ROW_COUNT();
SELECT CASE
	WHEN NOT @admin_input_valid THEN '未创建：请设置有效用户名和 BCrypt 密码哈希'
	WHEN @admin_created = 1 THEN '管理员已创建'
	WHEN EXISTS (SELECT 1 FROM sys_user WHERE role = 'ADMIN' AND enabled = 1)
		THEN '已存在启用的管理员，未修改账号'
	ELSE '用户名已存在，未修改账号'
END AS result;
