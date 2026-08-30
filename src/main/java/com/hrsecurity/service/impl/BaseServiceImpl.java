package com.hrsecurity.service.impl;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hrsecurity.common.BusinessException;
import com.hrsecurity.common.ResultCode;

/**
 * Service 实现公共基类：收敛"按 id 查库，查不到抛 404"这类重复代码。
 * 子类声明具体 Mapper 与实体泛型，用构造注入传入 baseMapper。
 */
public abstract class BaseServiceImpl<T extends BaseMapper<E>, E> {

    protected final T baseMapper;

    protected BaseServiceImpl(T baseMapper) {
        this.baseMapper = baseMapper;
    }

    /** 按 id 查询，不存在抛 404（错误文案由调用方指定） */
    protected E getOrThrow(Long id, String message) {
        E entity = baseMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), message);
        }
        return entity;
    }
}
