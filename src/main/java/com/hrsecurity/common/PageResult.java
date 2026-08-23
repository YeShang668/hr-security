package com.hrsecurity.common;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 分页返回结构：MyBatis-Plus Page 的简化出参，前端只需 total + records。
 */
@Data
@AllArgsConstructor
public class PageResult<T> {

    /** 总条数（用于前端分页条） */
    private long total;

    /** 当前页数据 */
    private List<T> records;
}
