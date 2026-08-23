package com.hrsecurity.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 员工出参 VO：员工信息 + 部门名（列表页直接展示，免去前端二次请求）。
 * 实体 SysEmployee 禁止直接出参，统一走该 VO。
 */
@Data
@Builder
public class EmployeeVO {

    private Long id;

    private String empNo;

    private String name;

    /** 1男 2女 */
    private Integer gender;

    private String phone;

    private String email;

    private Long deptId;

    /** 部门名（Service 层联查填充） */
    private String deptName;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate entryDate;

    /** 1在职 0离职 */
    private Integer status;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
}
