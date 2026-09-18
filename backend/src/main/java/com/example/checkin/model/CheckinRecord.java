package com.example.checkin.model;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.time.LocalDate;

/**
 * 打卡记录表 checkin_records 的持久化对象；一条记录对应一个业务日期。
 * Lombok 在编译时生成标准 JavaBean getter/setter，供 MyBatis 读取参数、映射结果和回填主键。
 * 保留 Java 默认无参构造；只生成访问方法，避免自动输出全部字段或改变对象相等语义。
 * 本对象只承载数据，不执行字段校验或业务计算。
 */
@Getter
@Setter
public class CheckinRecord {
    /** 数据库自增主键：插入前可为 null，由 MyBatis 在插入成功后回填。 */
    private Long id;

    /** 所属用户 ID；业务调用方须从服务端身份确定，不能直接信任前端输入。 */
    private Long userId;

    /** 习惯 ID，与 userId 一起受复合外键约束，必须匹配同一所有者。 */
    private Long habitId;

    /** Asia/Shanghai 时区下的业务日期，对应 SQL DATE，不包含时分秒。 */
    private LocalDate checkinDate;

    /** 实际打卡时间，按 UTC 约定填入，对应 DATETIME(3) 毫秒精度。 */
    private LocalDateTime checkedInAt;
}
