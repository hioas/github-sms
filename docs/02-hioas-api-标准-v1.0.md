# HIOAS API 描述标准 v1.0（hioas-api/1.0）

> 状态：**草案，待评审**
> 定位：用一份 JSON 描述文件定义「如何调用一个第三方 HTTP API（首期场景：短信发送渠道）」，由通用引擎解析并发起调用。新增渠道 = 新增一份描述文件，不写代码。
> 依据：`docs/01-渠道调研报告.md` 对 SMS4J 25 个渠道的逐一分析

---

## 1. 设计目标与原则

| # | 目标 | 说明 |
|---|------|------|
| G1 | 新增渠道零代码 | 常规渠道（明文密钥 / 哈希签名 / HMAC 签名链）只写描述文件 |
| G2 | 描述与密钥分离 | 描述文件定义「怎么调」，实例配置提供「用什么凭证」，互不混杂 |
| G3 | 签名可声明 | 不内置厂商专用签名器；用「派生值链 + 原语函数」组合表达任意签名，避免每接一个厂商加一段代码 |
| G4 | 可校验 | 提供 JSON Meta-Schema（draft-07），加载时机器校验 + 语义校验 |
| G5 | 有兜底 | 纯 SDK 渠道（如京东云）通过 SPI 适配器接入，描述文件声明 `protocol: java` |
| G6 | 兼容现有契约 | 引擎门面方法与 sms4j `SmsBlend` 对齐，返回结构对齐 `SmsResponse` |

**关键设计决策（已确认）**：签名采用「通用派生值链」而非「厂商签名策略库」。调研证明阿里 POP、腾讯 TC3、百度 BCE、华为 WSSE、七牛等全部可分解为 排序 / 编码 / 哈希 / HMAC / 拼接 原语（见 §8 示例与附录例 3、4）。代价是复杂渠道的描述文件较长（10~20 行派生链），收益是引擎永不因新签名算法而改代码。

**实现决策（2026-08-28 评审确认）**：
1. 签名机制：纯派生链 + 原语，不内置厂商签名策略库；
2. 表达式引擎：自研轻量实现（无第三方表达式库依赖）；
3. 项目形态：独立项目，门面方法与 `SmsBlend` 对齐、返回结构与 `SmsResponse` 对齐，但不依赖 SMS4J；
4. 首批范围：引擎 + 创蓝、助通、阿里云（代表 A/B/C 三级），腾讯云描述文件一并交付。

---

## 2. 文件体系

```
渠道描述文件  <channel>.api.json      —— 厂商 API 的调用定义，不含任何密钥，随版本管理分发
实例配置文件  <configId>.instance.json —— 部署侧凭证与行为参数，引用描述文件
```

运行时合并模型：`引擎 = 描述文件 + 实例配置`，实例配置中的 `config` 值填充描述文件 `config.fields` 声明的字段，形成表达式上下文 `config.*`。

实例配置文件结构：

```jsonc
{
  "schema": "hioas-instance/1.0",
  "channel": "zhutong",                 // 引用的描述文件渠道标识
  "configId": "zhutong-main",           // 实例标识，对齐 sms4j configId
  "config": { /* config.fields 声明的字段取值，密钥在此，不在描述文件 */ },
  "behavior": { /* 可选：覆盖描述文件的引擎行为默认值 */ }
}
```

描述文件顶层结构：

```jsonc
{
  "schema": "hioas-api/1.0",            // 固定，标准版本
  "channel": "zhutong",                 // 渠道唯一标识（小写）
  "title": "助通短信",
  "description": "助通科技短信平台",
  "protocol": "http",                   // http | java（SPI 兜底，见 §12）
  "config": { ... },                    // §4 配置字段声明
  "preRequests": { ... },               // §6 前置请求（可选）
  "operations": { ... },                // §7 操作定义（核心）
  "routing": [ ... ],                   // §10 发送路由（可选）
  "behavior": { ... }                   // §11 引擎行为（可选）
}
```

---

## 3. 运行时输入契约（引擎门面 → 描述文件）

引擎对外暴露与 sms4j `SmsBlend` 对齐的方法，并把入参规范化为以下**固定运行时变量**，供所有表达式引用：

| 变量 | 类型 | 说明 |
|------|------|------|
| `phones` | string[] | 接收号码列表（单发时为单元素数组） |
| `phone` | string | `phones[0]` 的便捷别名 |
| `message` | string | 原始文本内容（模板发送时可为空） |
| `templateId` | string | 模板 ID（未显式指定时取 `config.templateId`） |
| `vars` | map<string,string> | 模板变量，key=变量名 value=变量值 |

**门面方法 → 运行时变量的映射规则**（引擎内置，与 sms4j 语义一致）：

| 调用 | 映射 |
|------|------|
| `sendMessage(phone, message)` | `templateId=config.templateId`；`vars={config.templateName: message}` |
| `sendMessage(phone, vars)` | `templateId=config.templateId` |
| `sendMessage(phone, templateId, vars)` | 原样传入 |
| `massTexting(...)` | 同上，`phones` 为多元素数组 |

---

## 4. 配置字段声明 `config`

声明该渠道需要的凭证与参数（用于加载校验、文档生成、未来配置界面）。字段命名**沿用 sms4j 习惯名**，渠道特有字段自由扩展。

```jsonc
"config": {
  "fields": {
    "requestUrl":      { "type": "string", "required": true, "default": "https://api.mix2.zthysms.com/", "patternEndsWith": "/", "description": "接口地址，以 / 结尾" },
    "accessKeyId":     { "type": "string", "required": true, "label": "账号" },
    "accessKeySecret": { "type": "string", "required": true, "label": "密码", "sensitive": true },
    "signature":       { "type": "string", "label": "短信签名" },
    "templateId":      { "type": "string", "description": "默认模板ID" },
    "templateName":    { "type": "string", "description": "单变量模板的变量名" }
  }
}
```

| 属性 | 说明 |
|------|------|
| `type` | `string` / `number` / `boolean`（v1.0 仅三种标量） |
| `required` | 加载实例时校验非空 |
| `default` | 实例未提供时的默认值（**禁止在描述文件中放密钥类默认值**） |
| `sensitive` | `true` 时：日志输出脱敏、不参与调试打印 |
| `label` / `description` | 展示用 |
| `patternEndsWith` / `patternStartsWith` | 轻量格式校验 |

引用方式：`${config.<字段名>}`。实例配置中未声明而描述文件引用了的字段 → 加载期报错（快速失败）。

---

## 5. 表达式语言

### 5.1 插值

任何模板字符串中的 `${...}` 在渲染时求值。`${}` 外为字面量。整个值也可以是单个 `${...}`，此时保留求值结果的**原生类型**（数字、数组、对象、null），不强制转字符串。

### 5.2 命名空间

| 前缀 | 含义 |
|------|------|
| `config.<field>` | 实例配置字段（§4） |
| `phones` `phone` `message` `templateId` `vars` | 运行时输入（§3，无前缀直接引用） |
| `derive.<name>` | 当前操作的派生值（§7.2） |
| `pre.<request>.<field>` | 前置请求捕获值（§6） |
| `request.body` | **最终序列化后的请求体字符串**（用于对 body 做哈希/签名的场景，如腾讯、七牛） |
| `item` / `key` | `@each` 循环内的元素 / 键（§7.3.3） |
| `resp.$` / `resp.text` / `resp.status` | 响应解析（§9） |

### 5.3 类型与字节语义

类型集合：`string`、`number`、`boolean`、`list`、`map`、`bytes`、`null`。

- 字符串参与哈希/签名运算时按 **UTF-8** 取字节；
- `hmac*` 系列返回 `bytes`，可继续作为下一次 `hmac*` 的 key 或输入（支持腾讯/百度/天翼云的多级密钥派生）；
- `md5` / `sha1` / `sha256` 直接返回**小写 hex 字符串**；需要字节时用 `hmac` 系列；
- `+` 为字符串拼接（字节侧自动按 UTF-8 或 hex 规则转换——规范约定：`bytes + string` 视为对 bytes 的 hex 表示拼接是**禁止的**，必须显式 `hex()`，避免歧义）；
- 模板值为 `null` → 该 JSON 字段 / form 字段**整体省略**（用于条件字段）。

### 5.4 运算与布尔表达式

`==` `!=` `>` `<` `>=` `<=`、`&&` `||` `!`、`+`（拼接）、括号。用于 `successWhen`、`routing.when`、`validate.check` 及 `if()` 条件。`null == null` 为真；字符串比较区分大小写。

### 5.5 惰性求值与循环检测

`derive`、`request` 各部分构成一个**按需求值的模板图**：渲染 header/url 需要 `derive.x`，而 `derive.x` 需要 `request.body` 时，引擎自动按依赖顺序求值并**记忆化**（同名派生值只算一次——保证签名中的时间戳/随机数处处一致）。检测到循环依赖 → 加载期报错。

### 5.6 内置函数库（v1.0）

**时间与随机**

| 函数 | 说明 |
|------|------|
| `timestampSec()` | 当前秒级时间戳 |
| `timestampMs()` | 当前毫秒时间戳 |
| `uuid()` | 随机 UUID |
| `nonce(n)` | n 位随机字母数字 |
| `random(n)` | n 位随机数字 |
| `utcDate(fmt)` | 当前 UTC 时间，按 Java 风格格式串输出（如 `yyyy-MM-dd'T'HH:mm:ss'Z'`） |
| `dateOf(sec, fmt, tz)` | 秒级时间戳 → 指定时区格式串（tz 省略=UTC） |

**哈希 / HMAC / 编码**

| 函数 | 说明 |
|------|------|
| `md5(x)` `sha1(x)` `sha256(x)` | 小写 hex |
| `hmacSha1(key, x)` `hmacSha256(key, x)` | 返回 bytes，配合 `base64()`/`hex()` 输出 |
| `base64(x)` | 接受 string 或 bytes |
| `hex(x)` | bytes → 小写 hex |
| `urlEncode(x)` | 标准 UTF-8 URL 编码 |
| `urlEncodeRfc3986(x)` | `+`→`%20`、`*`→`%2A`、`%7E`→`~`（阿里云 POP 要求） |
| `urlDecode(x)` | |
| `upper(x)` `lower(x)` `trim(x)` | |

**字符串 / 集合**

| 函数 | 说明 |
|------|------|
| `join(list, sep)` | 拼接为字符串（逗号手机号等） |
| `prefix(list, p)` / `suffix(list, s)` | 逐元素加前后缀（`+86`、`【】`） |
| `ensurePrefix(list, p)` | 仅对未以 `p` 开头的元素补前缀（对齐 `addCodePrefixIfNot`） |
| `toJson(x)` | map/list → JSON 字符串 |
| `values(map)` | 取值为数组（腾讯/网易的变量数组） |
| `kvJoin(map, itemTpl, sep)` | `itemTpl` 为模板（逐项绑定 `key`/`value`）渲染后以 `sep` 连接；云片 `#${key}#=${value}`、梦网 `${key}=${urlEncode(value)}` |
| `merge(map1, map2)` | 合并两个 map |
| `sortedQueryString(map, encode)` | 按 key 升序拼 `k1=v1&k2=v2`；encode ∈ `none` `url` `rfc3986`（阿里/联麓/赛邮签名用） |
| `size(x)` | list/map 的元素数，或字符串长度 |
| `str(x)` | 显式转字符串（用于整体 `${}` 取值时放弃原生类型，如把数字时间戳以字符串提交） |
| `mapJoin(list, itemTpl, sep)` | 逐元素以 `item` 代入 `itemTpl` 渲染后用 `sep` 连接；`itemTpl` 为模板串，内部可含任意 `${}`（创蓝群发 `手机号,内容;` 拼接用） |
| `if(cond, a, b)` | 条件，b 可为 null（→ 字段省略） |
| `isBlank(x)` `contains(s, sub)` | |

> 函数库是标准的一部分，**新增函数 = 标准升版**；实现方不得私自扩展语义。未来版本可允许 `java:` 前缀的自定义函数扩展点（v1.0 不开放）。

---

## 6. 前置请求 `preRequests`（可选）

用于「先调一个接口拿值，再发正式请求」（赛邮的服务端时间戳）。按声明顺序执行，结果按 `capture` 提取后进入 `pre.<名字>.<字段>` 上下文。

```jsonc
"preRequests": {
  "serverTime": {
    "request": { "method": "GET", "url": "https://api-v4.mysubmail.com/service/timestamp" },
    "response": { "capture": { "ts": "$.timestamp" } }
  }
}
```

前置请求失败 → 整个发送失败（不做静默降级）。

---

## 7. 操作定义 `operations`

一个渠道可有多个操作（发短信、模板短信、余额查询、语音验证码……）。发短信类操作供门面方法路由调用（§10），其余操作通过引擎通用入口 `execute(opName, params)` 调用。

```jsonc
"operations": {
  "sendTemplate": {
    "description": "模板短信发送",
    "validate": [ { "check": "size(phones) <= 2000", "message": "手机号码最多支持2000个" } ],
    "derive": { ... },                    // §7.2
    "request": { ... },                   // §7.3
    "response": { ... },                  // §9
    "mass": { "strategy": "join" }        // §7.4
  }
}
```

### 7.1 `validate`（可选）

发送前校验，`check` 为布尔表达式，不满足时以 `message` 抛出发送失败（对齐各渠道现有的参数校验语义，如助通的 2000 上限、内容必须含 `【`）。

### 7.2 `derive` 派生值

```jsonc
"derive": {
  "tKey": "${timestampSec()}",
  "password": "${md5(md5(config.accessKeySecret) + derive.tKey)}"
}
```

- 键即名字，值为标量表达式或**结构化模板**（对象/数组，内部元素同样可含 `${}`）；
- 后声明的可引用先声明的；引用无强制顺序（惰性求值，§5.5）；
- 派生值通过 `${derive.<name>}` 在 url/headers/query/body/response 中使用；
- 典型用途：时间戳、随机数（保证多处一致）、多级签名链、签名串。

### 7.3 `request` 请求构造

```jsonc
"request": {
  "method": "POST",                       // GET | POST（v1.0）
  "url": "${config.requestUrl}v2/sendSmsTp",
  "headers": { "Content-Type": "application/json;charset=utf-8" },
  "query": { },                           // GET/POST 均可，追加为查询串
  "body": { ... }                         // GET 省略；见 §7.3.2
}
```

#### 7.3.1 url / query / headers

均为模板。`query` 为 map，值求值后 URL 编码拼接（数组值重复 key）。GET 请求只允许 `query`。

#### 7.3.2 body

```jsonc
"body": {
  "contentType": "json",                  // json | form | raw
  "encoding": "none",                     // none | base64 | urlEncode —— 对最终序列化结果再编码（mas 的 base64 整体封装）
  "template": { ... }                     // json/form：map 模板；raw：字符串模板
}
```

| contentType | 序列化 | 说明 |
|------|--------|------|
| `json` | 模板 map → JSON | 保留原生类型（数字不加引号） |
| `form` | 模板 map → `application/x-www-form-urlencoded` | 值 URL 编码 |
| `raw` | 字符串模板直接作为 body | 配合 `encoding` 使用 |

`template` 也可以整体是一个 `${derive.xxx}`（引用 map 型派生值），便于签名与提交复用同一参数表（阿里云：签名用全量参数，body 只提交业务参数）。

#### 7.3.3 循环指令 `@each`

用于「按号码逐条展开数组」（助通 records）：

```jsonc
"records": {
  "@each": "${phones}",
  "@as": { "mobile": "${item}", "tpContent": "${vars}" }
}
```

求值为数组，每个元素是 `@as` 模板以 `item` 代入后的渲染结果。仅允许出现在模板对象值中，不允许嵌套。

#### 7.3.4 null 省略

任何模板值求值为 `null` → 该字段省略（条件字段，如 mas 的可选字段）。

### 7.4 `mass` 群发策略

| 策略 | 行为 |
|------|------|
| `join`（默认） | `phones` 整体交给一次请求（数组、`join(phones,",")` 等由模板决定） |
| `fanout` | 引擎逐号码循环执行该操作（布丁云模式），任一失败即记录并继续，汇总返回 |

---

## 8. 签名表达方式（标准立场）

**不内置厂商签名器。** 一切签名 = 派生值链 + §5.6 函数。三个复杂度台阶（完整示例见附录）：

| 台阶 | 例 | 表达 |
|------|----|------|
| 明文/简单哈希 | 云片、创蓝、助通 | 0~2 个派生值 |
| 头签名 + 哈希链 | 华为 WSSE、网易 CheckSum、容联、Basic Auth | 3~5 个派生值，结果进 `headers` |
| 规范化请求签名 | 阿里 POP、腾讯 TC3、百度 BCE、天翼云 EOP、七牛 | 8~12 个派生值：构造参数表 → `sortedQueryString` → 多级 `hmac` → 拼装 `Authorization`；需要 body 哈希处用 `${request.body}` |

---

## 9. 响应解析 `response`

```jsonc
"response": {
  "contentType": "json",                  // json | text
  "successWhen": "$.code <= 200",         // 布尔表达式，路径以 $ 开头
  "smsId": "$.msgId",                     // 可选：回执ID提取路径
  "message": "$.msg"                      // 可选：错误信息提取路径
}
```

- `json`：`$.a.b[0].c` 简单路径（点号 + 数组下标），根可写 `$`；
- `text`：用 `resp.text` 做 `contains(...)` 等判定（一信通）；
- 条件表达式的值域支持：`==` 数字/字符串、`!= null`（七牛「error 为空即成功」：`$.error == null`）、`<=`（助通 `$.code <= 200`）、`&&` 复合（mas）；
- 输出：`{ success: boolean, data: <原始响应>, smsId?, message? }`，与 sms4j `SmsResponse` 对齐（`data` 保留厂商原返回体）。

---

## 10. 发送路由 `routing`（可选）

门面方法选定操作的过程：

```jsonc
"routing": [
  { "when": "isBlank(templateId)", "to": "sendCustom" },
  { "to": "sendTemplate" }
]
```

- 自上而下，第一个 `when` 为真的命中；无 `when` 为兜底；
- 单操作渠道可省略 `routing`；
- `when` 可引用运行时变量与 `config.*`（助通「模板三要素任一为空走自定义短信」、鼎众双 action 均可表达）。

---

## 11. 引擎行为 `behavior`（可选）

```jsonc
"behavior": {
  "maxRetries": 3, "retryIntervalMs": 2000,     // 失败重试，对齐 sms4j 语义
  "timeoutMs": 8000,
  "proxy": { "enable": false, "host": "", "port": 0 }
}
```

描述文件给默认值，实例配置可覆盖。限流（每分钟/每日上限）属于平台能力，不在描述文件表达。

---

## 12. SPI 兜底：`protocol: java`

纯 SDK 渠道（京东云）无法用 HTTP 描述：

```jsonc
{
  "schema": "hioas-api/1.0",
  "channel": "jdcloud",
  "protocol": "java",
  "java": { "class": "com.hioas.sms.adapter.JdcloudAdapter" },
  "config": { "fields": { ... } }
}
```

适配类实现引擎定义的 `HioasSmsAdapter` 接口（方法签名与运行时契约一致，返回同一响应结构）。注册发现用 JDK SPI。**这是唯一允许写代码的扩展点。**

---

## 13. 安全规范

1. 描述文件**禁止**出现密钥、密码、令牌（加载期扫描 `accessKeySecret`/`password` 等字段的 `default` 值直接拒绝）；
2. `sensitive: true` 字段在日志中输出为 `***`；
3. 表达式无副作用、无外部 IO（除声明的 `preRequests`）、无递归用户输入；函数库白名单封闭；
4. 实例配置文件由部署系统管理，不进代码仓库。

---

## 14. 渠道覆盖结论

| 覆盖方式 | 渠道 |
|----------|------|
| 纯描述文件（23 个） | 全部 A/B 级 + 阿里、百度、天翼云、华为、七牛、腾讯 |
| SPI 适配器（1 个） | 京东云 |
| 过渡期（先 SPI 后转描述文件） | unisms |

---

## 15. 版本演进

- 描述文件以 `schema` 字段声明所依标准版本；引擎按主次版本兼容（1.x 内向后兼容）；
- 新增函数、新增 contentType、新增指令 → 次版本升级并在标准登记。

---

## 附录：示例索引

| 文件 | 展示能力 |
|------|----------|
| `examples/chuanglan.api.json` | A 级：明文密钥、JSON body、群发参数拼接 |
| `examples/zhutong.api.json` | B 级：派生值（双重 MD5）、`@each`、双操作路由、validate |
| `examples/aliyun.api.json` | C 级：POP 签名（参数排序 + RFC3986 + HMAC-SHA1，签名进 URL，body/query 分离） |
| `examples/tencent.api.json` | C 级：TC3 四步密钥派生 + `${request.body}` 参与签名 + 数组下标响应路径 |
| `examples/zhutong.instance.json` | 实例配置样例 |

---

## 16. v1.0.1 增补（批量接入 A/B 级渠道时引入）

以下扩展均向后兼容，`schema` 仍声明 `hioas-api/1.0`：

1. **`ensurePrefix(list, p)`**：仅对未以 `p` 开头的元素补前缀，对齐 sms4j `addCodePrefixIfNot`（旦米、云片批量等）；
2. **`kvJoin` 模板语义**：`itemTpl` 升级为模板，逐项绑定 `key`/`value` 后渲染，支持在模板内调用函数（如梦网 `${key}=${urlEncode(value)}`）；
3. **`request.body.charset`**：序列化字符集声明，缺省 `UTF-8`（联通一信通为 `gbk`）；
4. **`response.smsId` / `response.message` 放宽为表达式**：可用 `urlDecode($.desc)` 等函数加工后提取；
5. **`operation.inputs`**：操作声明额外运行时变量（供通用入口 `execute()` 注入），加载期纳入引用白名单（极光 `verify` 的 `msgId`/`code`）。
