-- 修复 seed 导入时的二次编码乱码：admin 昵称应显示为「管理员」
-- 必须用 --default-character-set=utf8mb4 且本文件保持 UTF-8 编码执行
UPDATE `sys_user` SET `nickname` = '管理员' WHERE `id` = 1;
