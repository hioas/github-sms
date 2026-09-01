package com.hioas.sms.schema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 操作定义（标准 §7）。
 */
public class OperationSpec {

    public String description;
    /** 通用入口 execute() 注入的额外运行时变量声明 */
    public Map<String, FieldSpec> inputs;
    public List<ValidateRule> validate = new ArrayList<>();
    /** 派生值：名称 → 模板（字符串/对象/数组） */
    public Map<String, Object> derive = new LinkedHashMap<>();
    public RequestSpec request;
    public ResponseSpec response;
    public MassSpec mass;

    public String massStrategy() {
        return mass == null ? "join" : mass.strategy;
    }
}
