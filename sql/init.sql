-- ============================================
-- hr-security 初始化脚本
-- 第 1 周：sys_user（认证登录）
-- 第 2 周：RBAC 四表 + sys_dept + sys_employee（含种子数据）
-- 第 6 周：员工敏感字段改密文列（AES-256-GCM）+ 身份证检索哈希列 + 模拟旧系统明文表
-- 第 7 周：sys_audit_log（审计日志）+ sys_data_key（KEK/DEK 两级密钥的 DEK 表）
-- 执行方式：mysql --default-character-set=utf8mb4 -u root < sql/init.sql
-- 注意：本脚本会 DROP 重建所有表，仅用于开发环境初始化
-- ============================================

CREATE DATABASE IF NOT EXISTS hr_security
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE hr_security;

DROP TABLE IF EXISTS legacy_employee_plain;
DROP TABLE IF EXISTS sys_audit_log;
DROP TABLE IF EXISTS sys_data_key;
DROP TABLE IF EXISTS sys_role_permission;
DROP TABLE IF EXISTS sys_user_role;
DROP TABLE IF EXISTS sys_permission;
DROP TABLE IF EXISTS sys_role;
DROP TABLE IF EXISTS sys_employee;
DROP TABLE IF EXISTS sys_dept;
DROP TABLE IF EXISTS sys_user;

-- ---------- 第 1 周：用户表 ----------
CREATE TABLE sys_user (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
  username    VARCHAR(50)  NOT NULL UNIQUE COMMENT '登录名',
  password    VARCHAR(100) NOT NULL COMMENT 'BCrypt 密文，禁止明文',
  nickname    VARCHAR(50)  DEFAULT NULL COMMENT '姓名',
  status      TINYINT      NOT NULL DEFAULT 1 COMMENT '1启用 0禁用',
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) COMMENT '用户表';

-- ---------- 第 2 周：RBAC 三表 ----------
CREATE TABLE sys_role (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  role_code   VARCHAR(50) NOT NULL UNIQUE COMMENT '角色编码，如 ADMIN/EMPLOYEE',
  role_name   VARCHAR(50) NOT NULL COMMENT '角色名',
  status      TINYINT     NOT NULL DEFAULT 1 COMMENT '1启用 0禁用',
  created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) COMMENT '角色表';

CREATE TABLE sys_permission (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  perm_code   VARCHAR(100) NOT NULL UNIQUE COMMENT '权限编码，如 employee:list',
  perm_name   VARCHAR(50)  NOT NULL COMMENT '权限名',
  status      TINYINT      NOT NULL DEFAULT 1,
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) COMMENT '权限表';

CREATE TABLE sys_user_role (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id     BIGINT NOT NULL COMMENT '用户id',
  role_id     BIGINT NOT NULL COMMENT '角色id',
  UNIQUE KEY uk_user_role (user_id, role_id)
) COMMENT '用户-角色关联表';

CREATE TABLE sys_role_permission (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  role_id       BIGINT NOT NULL COMMENT '角色id',
  permission_id BIGINT NOT NULL COMMENT '权限id',
  UNIQUE KEY uk_role_perm (role_id, permission_id)
) COMMENT '角色-权限关联表';

-- ---------- 第 2 周：部门/员工表 ----------
CREATE TABLE sys_dept (
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
-- 长度 512 是留余量：18 位身份证密文 Base64 后约 100 字符，工资更短
CREATE TABLE sys_employee (
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
  -- 可检索性设计：密文无法 like/等值查询，另存 HMAC-SHA256(身份证) 专供精确检索与唯一校验；
  -- 用带密钥的 HMAC 而非裸 SHA-256，攻击者拿到库也造不出彩虹表（详见 docs/crypto-design.md）
  id_card_hash   CHAR(64)     DEFAULT NULL COMMENT '身份证 HMAC-SHA256(hex)，不可逆，仅用于精确检索',
  status         TINYINT      NOT NULL DEFAULT 1 COMMENT '逻辑删除位：1在职(未删) 0离职(已删)',
  created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_emp_id_card_hash (id_card_hash)
) COMMENT '员工表（敏感字段密文存储）';

-- ---------- 种子数据 ----------
-- 角色
INSERT INTO sys_role (role_code, role_name) VALUES ('ADMIN', '管理员'), ('EMPLOYEE', '普通员工');

-- 权限编码：第 6 周新增 employee:sensitive:read（查看敏感字段明文），
-- 这是"脱敏是默认、明文是例外"的落点：没这个权限的角色，接口只给脱敏值
INSERT INTO sys_permission (perm_code, perm_name) VALUES
  ('employee:list', '查看员工'), ('employee:add', '新增员工'),
  ('employee:update', '修改员工'), ('employee:delete', '删除员工'),
  ('dept:list', '查看部门'), ('dept:manage', '管理部门'),
  ('employee:sensitive:read', '查看员工敏感信息（身份证/银行卡/工资明文）');

-- admin 内置账号（密码 Hr@123456 的 BCrypt 密文，由 spring-security-crypto 本地生成）
-- 第 8 周安全加固：演示口令从 123456 改为 Hr@123456 —— 原口令连"8 位含字母数字"的注册口
-- 令策略都过不了，种子数据却留着一个更弱的口令，属于典型的"策略只在入口卡、内部留后门"。
-- 注意：注册接口默认分配 EMPLOYEE，ADMIN 角色只能由本种子数据或手动 SQL 授予
INSERT INTO sys_user (username, password, nickname, status) VALUES
  ('admin', '$2a$10$tLalfPvn3o0EcRVDT7eIIegGWCQTgF.yNMQtXWPZpVwANcDHKesh6', '管理员', 1);

-- 角色-权限绑定：ADMIN 全权限（含 employee:sensitive:read），EMPLOYEE 只读（员工/部门查看，无敏感明文权限）
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r, sys_permission p WHERE r.role_code = 'ADMIN';
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r, sys_permission p
WHERE r.role_code = 'EMPLOYEE' AND p.perm_code IN ('employee:list', 'dept:list');

-- admin 绑 ADMIN 角色
INSERT INTO sys_user_role (user_id, role_id)
SELECT u.id, r.id FROM sys_user u, sys_role r WHERE u.username = 'admin' AND r.role_code = 'ADMIN';

-- 演示部门/员工（便于联调分页与联查）
-- 注意：种子数据**不写敏感明文**（红线）。E001~E003 的身份证/手机号/银行卡/工资
-- 由"历史数据加密迁移"从下方 legacy_employee_plain 加密刷入（见 POST /api/admin/crypto/backfill）
INSERT INTO sys_dept (dept_name, sort) VALUES ('技术部', 1), ('人事部', 2), ('财务部', 3);
INSERT INTO sys_employee (emp_no, name, gender, email, dept_id, entry_date) VALUES
  ('E001', '张三', 1, 'zhangsan@hr.com', 1, '2024-03-01'),
  ('E002', '李四', 2, 'lisi@hr.com', 2, '2024-06-15'),
  ('E003', '王五', 1, 'wangwu@hr.com', 1, '2025-01-10');

-- ---------- 第 6 周：模拟"旧系统明文表"（历史数据加密迁移的数据源） ----------
-- 真实迁移场景：老 HR 系统把身份证/手机号/银行卡/工资明文存在表/导出文件里，
-- 迁移程序读明文 → AES-256-GCM 加密 → 写入新表密文列（幂等，可重复执行）。
-- 本表仅为演示与回归保留，**迁移完成后应执行 DROP TABLE legacy_employee_plain 清空明文源**。
CREATE TABLE legacy_employee_plain (
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

-- ---------- 第 7 周：审计日志表 ----------
-- 回答五个问题：谁（user_id + username 冗余）、何时（created_at）、做了什么（operation）、
-- 对什么做的（target_type + target_id）、来自哪（ip + user_agent）。
-- username 冗余存的理由：用户改名或被删除后，审计记录仍要能说清"当时是谁"。
-- 设计红线：detail 只记"看过/改过什么"，**绝不写入身份证、手机号等敏感明文**——
-- 否则审计表自己变成一张明文敏感数据表（见 docs/audit-design.md）。
CREATE TABLE sys_audit_log (
  id          BIGINT       PRIMARY KEY AUTO_INCREMENT,
  user_id     BIGINT       DEFAULT NULL COMMENT '操作者用户id（取不到时为 NULL，如后台任务/未登录）',
  username    VARCHAR(50)  DEFAULT NULL COMMENT '操作者用户名（冗余存储，改名/删除后仍可追溯）',
  operation   VARCHAR(100) NOT NULL COMMENT '操作类型，如 查看员工敏感信息',
  target_type VARCHAR(50)  DEFAULT NULL COMMENT '操作对象类型：EMPLOYEE/DEPT/USER/CRYPTO/KEY',
  target_id   VARCHAR(64)  DEFAULT NULL COMMENT '操作对象id（字符串，兼容非数字主键）',
  detail      VARCHAR(500) DEFAULT NULL COMMENT '补充说明（禁止写入敏感明文）',
  result      VARCHAR(16)  NOT NULL DEFAULT 'SUCCESS' COMMENT 'SUCCESS/FAILURE：失败也要记，越权尝试同样有审计价值',
  ip          VARCHAR(64)  DEFAULT NULL COMMENT '客户端 IP（优先取 X-Forwarded-For 首个地址）',
  user_agent  VARCHAR(255) DEFAULT NULL COMMENT '客户端 User-Agent',
  created_at  DATETIME     NOT NULL COMMENT '事件发生时间（异步落库，记事件时间而非插入时间）',
  KEY idx_audit_created_at (created_at),
  KEY idx_audit_user_id (user_id),
  KEY idx_audit_operation (operation)
) COMMENT '审计日志表：敏感数据访问与权限变更的追责依据';

-- ---------- 第 7 周：DEK（数据加密密钥）表，KEK/DEK 两级密钥的落点 ----------
-- 为什么 DEK 要落库、KEK 不落库（面试必答）：
--   KEK（密钥加密密钥，来自环境变量/KMS）永远不落库，只用于"解开 DEK"；
--   DEK 才是真正加密业务字段的密钥，它以**密文形式**存在这张表里。
--   好处：换 DEK 只需重加密业务数据，KEK 不受影响；KEK 泄露面也最小。
-- 本表刻意**不预置种子行**：encrypted_dek 是用当前环境的 KEK 加出来的，
-- 写死在 init.sql 里等于把密钥材料提交进 git（红线），且换个 KEK 就再也解不开。
-- 首次启动由 KekDekKeyProvider 引导生成 k1（见 docs/key-management.md）。
CREATE TABLE sys_data_key (
  id            BIGINT       PRIMARY KEY AUTO_INCREMENT,
  key_id        VARCHAR(32)  NOT NULL COMMENT 'DEK 版本号，写入密文 v1:{keyId}:{iv}:{ct}',
  encrypted_dek VARCHAR(512) NOT NULL COMMENT 'DEK 密文（用 KEK 做 AES-256-GCM 信封加密），绝不存明文密钥',
  status        VARCHAR(16)  NOT NULL COMMENT 'ACTIVE=当前加密密钥 / RETIRED=已退役仍可解密 / DISABLED=停用且拒绝解密',
  created_at    DATETIME     NOT NULL COMMENT '该 DEK 上线时间',
  retired_at    DATETIME     DEFAULT NULL COMMENT '退役时间（可回答"哪个 keyId 什么时候上线/退役"）',
  UNIQUE KEY uk_data_key_id (key_id),
  KEY idx_data_key_status (status)
) COMMENT '数据加密密钥表：DEK 仅以 KEK 加密后的密文形式落库';
