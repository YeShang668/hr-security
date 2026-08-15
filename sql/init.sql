-- ============================================
-- 第 1 周：用户表（认证登录）
-- 执行方式：mysql -u root < sql/init.sql
-- ============================================

CREATE DATABASE IF NOT EXISTS hr_security
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE hr_security;

DROP TABLE IF EXISTS sys_user;
CREATE TABLE sys_user (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
  username    VARCHAR(50)  NOT NULL UNIQUE COMMENT '登录名',
  password    VARCHAR(100) NOT NULL COMMENT 'BCrypt 密文，禁止明文',
  nickname    VARCHAR(50)  DEFAULT NULL COMMENT '姓名',
  status      TINYINT      NOT NULL DEFAULT 1 COMMENT '1启用 0禁用',
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) COMMENT '用户表';
