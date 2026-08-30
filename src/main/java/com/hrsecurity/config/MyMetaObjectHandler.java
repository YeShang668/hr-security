package com.hrsecurity.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 公共字段自动填充：created_at/updated_at 由框架统一维护。
 * 实体字段标 @TableField(fill = FieldFill.INSERT / INSERT_UPDATE) 即可，
 * 业务代码不再手工 set 时间，与数据库 DEFAULT CURRENT_TIMESTAMP 语义一致。
 * （sys_user_role / sys_role_permission 无时间戳字段，不受影响。）
 */
@Component
public class MyMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();
        this.strictInsertFill(metaObject, "createdAt", LocalDateTime.class, now);
        this.strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, now);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        this.strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
    }
}
