-- ============================================
-- 初始管理员 seed（一次性手动执行，启动零副作用）
-- 使用: docker exec -i ragent-mysql mysql -uroot -proot ragent < sql/seed_admin.sql
-- 账号: admin / admin123 （BCrypt 哈希由 bcryptjs 生成，已验证）
-- ============================================
INSERT INTO `sys_user` (`id`, `username`, `password`, `nickname`, `role`)
VALUES (1, 'admin', '$2b$10$gMVVZJCl1G49xHgfOWNugO6JQqOuQ1UBDUGXTNnrntIm6OXkA3GCi', '管理员', 'ADMIN')
ON DUPLICATE KEY UPDATE `username` = VALUES(`username`);
