package com.hrsecurity.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hrsecurity.entity.SysEmployee;

/**
 * 继承 BaseMapper 即拥有单表 CRUD，无需手写 SQL。
 * @TableLogic 生效位置：BaseMapper 的 select/delete 会自动拼接 status=1 条件。
 */
public interface SysEmployeeMapper extends BaseMapper<SysEmployee> {
}
