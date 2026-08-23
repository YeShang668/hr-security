package com.hrsecurity.service;

import com.hrsecurity.dto.DeptDTO;
import com.hrsecurity.entity.SysDept;

import java.util.List;

/**
 * 部门管理：平铺列表 + 增删改（多级组织架构 9 月演进，本周不过度设计）。
 */
public interface DeptService {

    List<SysDept> list();

    SysDept create(DeptDTO dto);

    SysDept update(Long id, DeptDTO dto);

    void delete(Long id);
}
