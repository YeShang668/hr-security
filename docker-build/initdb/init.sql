-- ============================================
-- hr-security Docker 首次初始化脚本
-- 挂载到 mysql 容器的 /docker-entrypoint-initdb.d/，仅当数据卷为空（首次启动）时执行一次
-- 注意：与本地 sql/init.sql 的区别——不含 DROP TABLE（空库无需删），全部 IF NOT EXISTS 幂等
-- 第 6 周：sys_employee 敏感字段改密文列（AES-256-GCM）+ 身份证检索哈希列 + 模拟旧系统明文表
-- 表结构与 sql/init.sql 保持一致（utf8mb4 红线）
-- ============================================

-- 官方 mysql 镜像 entrypoint 用 mysql 客户端执行本文件时客户端默认 latin1，
-- 会把 UTF-8 中文误按 latin1 发送造成双重编码乱码；显式声明会话字符集
SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS hr_security
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE hr_security;

-- ---------- 第 1 周：用户表 ----------
CREATE TABLE IF NOT EXISTS sys_user (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
  username    VARCHAR(50)  NOT NULL UNIQUE COMMENT '登录名',
  password    VARCHAR(100) NOT NULL COMMENT 'BCrypt 密文，禁止明文',
  nickname    VARCHAR(50)  DEFAULT NULL COMMENT '姓名',
  status      TINYINT      NOT NULL DEFAULT 1 COMMENT '1启用 0禁用',
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) COMMENT '用户表';

-- ---------- 第 2 周：RBAC 表 ----------
CREATE TABLE IF NOT EXISTS sys_role (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  role_code   VARCHAR(50) NOT NULL UNIQUE COMMENT '角色编码，如 ADMIN/EMPLOYEE',
  role_name   VARCHAR(50) NOT NULL COMMENT '角色名',
  status      TINYINT     NOT NULL DEFAULT 1 COMMENT '1启用 0禁用',
  created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) COMMENT '角色表';

CREATE TABLE IF NOT EXISTS sys_permission (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  perm_code   VARCHAR(100) NOT NULL UNIQUE COMMENT '权限编码，如 employee:list',
  perm_name   VARCHAR(50)  NOT NULL COMMENT '权限名',
  status      TINYINT      NOT NULL DEFAULT 1,
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) COMMENT '权限表';

CREATE TABLE IF NOT EXISTS sys_user_role (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id     BIGINT NOT NULL COMMENT '用户id',
  role_id     BIGINT NOT NULL COMMENT '角色id',
  UNIQUE KEY uk_user_role (user_id, role_id)
) COMMENT '用户-角色关联表';

CREATE TABLE IF NOT EXISTS sys_role_permission (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  role_id       BIGINT NOT NULL COMMENT '角色id',
  permission_id BIGINT NOT NULL COMMENT '权限id',
  UNIQUE KEY uk_role_perm (role_id, permission_id)
) COMMENT '角色-权限关联表';

-- ---------- 第 2 周：部门/员工表 ----------
CREATE TABLE IF NOT EXISTS sys_dept (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  parent_id   BIGINT      NOT NULL DEFAULT 0 COMMENT '上级部门，0=顶级',
  dept_name   VARCHAR(50) NOT NULL,
  sort        INT         NOT NULL DEFAULT 0 COMMENT '排序',
  status      TINYINT     NOT NULL DEFAULT 1,
  created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) COMMENT '部门表';

-- 第 6 周改造：敏感字段全部改为密文列，**明文列一律不保留**（不允许密文与明文并存）
-- 密文格式 v1:{keyId}:{ivBase64}:{cipherBase64}，自带版本号与 keyId，为下周密钥轮换留位置
CREATE TABLE IF NOT EXISTS sys_employee (
  id             BIGINT PRIMARY KEY AUTO_INCREMENT,
  emp_no         VARCHAR(20)  NOT NULL UNIQUE COMMENT '工号',
  name           VARCHAR(50)  NOT NULL COMMENT '姓名',
  gender         TINYINT      DEFAULT 1 COMMENT '1男 2女',
  phone_enc      VARCHAR(512) DEFAULT NULL COMMENT '手机号密文（AES-256-GCM）',
  id_card_enc    VARCHAR(512) DEFAULT NULL COMMENT '身份证号密文（AES-256-GCM）',
  bank_card_enc  VARCHAR(512) DEFAULT NULL COMMENT '银行卡号密文（AES-256-GCM）',
  salary_enc     VARCHAR(512) DEFAULT NULL COMMENT '工资密文（工资属敏感个人信息，同样字段级加密）',
  email          VARCHAR(100) DEFAULT NULL,
  dept_id        BIGINT       NOT NULL COMMENT '部门id',
  entry_date     DATE         DEFAULT NULL COMMENT '入职日期',
  id_card_hash   CHAR(64)     DEFAULT NULL COMMENT '身份证 HMAC-SHA256(hex)，不可逆，仅用于精确检索',
  status         TINYINT      NOT NULL DEFAULT 1 COMMENT '逻辑删除位：1在职(未删) 0离职(已删)',
  created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_emp_id_card_hash (id_card_hash)
) COMMENT '员工表（敏感字段密文存储）';

-- ---------- 种子数据（与 sql/init.sql 一致） ----------
INSERT INTO sys_role (role_code, role_name) VALUES ('ADMIN', '管理员'), ('EMPLOYEE', '普通员工');

-- 权限编码：第 6 周新增 employee:sensitive:read（查看敏感字段明文）
INSERT INTO sys_permission (perm_code, perm_name) VALUES
  ('employee:list', '查看员工'), ('employee:add', '新增员工'),
  ('employee:update', '修改员工'), ('employee:delete', '删除员工'),
  ('dept:list', '查看部门'), ('dept:manage', '管理部门'),
  ('employee:sensitive:read', '查看员工敏感信息（身份证/银行卡/工资明文）');

-- admin 内置账号（密码 123456 的 BCrypt 密文，由 spring-security-crypto 本地生成）
INSERT INTO sys_user (username, password, nickname, status) VALUES
  ('admin', '$2a$10$G6mbJSrSwLMXuHLBPY4cOu0O3lrlo/eoPP0Gpzce2u7kWYhZd0GUS', '管理员', 1);

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r, sys_permission p WHERE r.role_code = 'ADMIN';
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r, sys_permission p
WHERE r.role_code = 'EMPLOYEE' AND p.perm_code IN ('employee:list', 'dept:list');

INSERT INTO sys_user_role (user_id, role_id)
SELECT u.id, r.id FROM sys_user u, sys_role r WHERE u.username = 'admin' AND r.role_code = 'ADMIN';

INSERT INTO sys_dept (dept_name, sort) VALUES ('技术部', 1), ('人事部', 2), ('财务部', 3);
-- 种子数据不写敏感明文（红线）：E001~E003 的敏感字段由加密迁移刷入（见 /api/admin/crypto/backfill）
INSERT INTO sys_employee (emp_no, name, gender, email, dept_id, entry_date) VALUES
  ('E001', '张三', 1, 'zhangsan@hr.com', 1, '2024-03-01'),
  ('E002', '李四', 2, 'lisi@hr.com', 2, '2024-06-15'),
  ('E003', '王五', 1, 'wangwu@hr.com', 1, '2025-01-10');

-- ---------- 第 6 周：模拟"旧系统明文表"（历史数据加密迁移的数据源，见 docs/crypto-design.md） ----------
CREATE TABLE IF NOT EXISTS legacy_employee_plain (
  emp_no    VARCHAR(20) NOT NULL COMMENT '工号（对应 sys_employee.emp_no）',
  id_card   VARCHAR(32) DEFAULT NULL COMMENT '身份证明文（迁移源）',
  phone     VARCHAR(20) DEFAULT NULL COMMENT '手机号明文（迁移源）',
  bank_card VARCHAR(32) DEFAULT NULL COMMENT '银行卡明文（迁移源）',
  salary    VARCHAR(20) DEFAULT NULL COMMENT '工资明文（迁移源）'
) COMMENT '旧系统明文员工表（模拟，仅作加密迁移数据源）';

INSERT INTO legacy_employee_plain (emp_no, id_card, phone, bank_card, salary) VALUES
  ('E001', '110101199003071234', '13800000001', '6222020200112233445', '18000.00'),
  ('E002', '310104199205206789', '13800000002', '6217001210099887766', '16500.50'),
  ('E003', '500103199801153456', '13800000003', '6228480402564890018', '21000.00');
