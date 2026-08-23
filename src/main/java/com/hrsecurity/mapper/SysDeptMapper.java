package com.hrsecurity.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hrsecurity.entity.SysDept;

/**
 * 继承 BaseMapper 即拥有单表 CRUD，无需手写 SQL。
 */
public interface SysDeptMapper extends BaseMapper<SysDept> {
}
