package com.hrsecurity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 部门新增/修改请求参数。
 */
@Data
public class DeptDTO {

    @NotBlank(message = "部门名称不能为空")
    @Size(max = 50, message = "部门名称最长 50 个字符")
    private String deptName;

    /** 上级部门 id，0=顶级（默认顶级） */
    private Long parentId;

    /** 排序号，越小越靠前 */
    private Integer sort;

    /** 1启用 0禁用（默认启用） */
    private Integer status;
}
