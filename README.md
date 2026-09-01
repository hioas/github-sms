# hioas-sms

配置驱动的第三方短信渠道调用引擎 —— `hioas-api/1.0` 标准的参考实现。

**核心命题**：新增一个短信渠道（或其他第三方 HTTP API 接入）= 写一份 JSON 描述文件，不写代码。

## 背景

SMS4J 的 `sms4j-provider` 中 25 个渠道全部用硬编码 Java 构造 HTTP 请求。本项目把「怎么调第三方接口」抽象为声明式描述文件，由通用引擎解析执行。调研与分级见 `docs/01-渠道调研报告.md`。

## 目录

```
hioas-sms/
├── docs/                              标准与调研
│   ├── 01-渠道调研报告.md              25 渠道调用特征矩阵（A/B/C 分级）
│   ├── 02-hioas-api-标准-v1.0.md      ★ 描述标准正文（评审已通过）
│   ├── schema/                        draft-07 Meta-Schema（机器校验）
│   └── examples/                      示例描述文件、实例配置与联调用例（sms.http）
└── src/
    ├── main/java/com/hioas/sms/
    │   ├── HioasSms.java              入口：描述文件+实例配置 → 渠道实例
    │   ├── expr/                      自研表达式引擎（解析/求值/函数库/模板渲染）
    │   ├── schema/                    描述文件模型 + 加载 + 语义校验
    │   ├── core/                      执行编排、门面（对齐 sms4j SmsBlend）
    │   ├── http/                      HTTP 发送（JDK HttpClient，可替换）
    │   └── spi/                       protocol=java 兜底适配器接口
    ├── main/resources/channels/       已内置 19 个描述文件（见下）
    └── test/                          62 个测试（含多渠道签名交叉校验）
```

**内置渠道（19）**：

| 级别 | 渠道 |
|------|------|
| A | 创蓝、布丁云、旦米、鼎众、亿美、一信通、云片、梦网* |
| B | 助通、容联、互亿、极光、联麓、螺丝帽、移动云MAS、网易、赛邮 |
| C | 阿里云、腾讯云 |

> *梦网实际带 MD5 派生签名，归入 A 为调研口径；描述文件均按真实算法表达。
> 京东云为纯 SDK，走 `protocol: java` SPI；unisms 建议先 SPI 后转描述文件。

## 快速开始

```java
SmsChannel ch = HioasSms.classpathChannel(
        "channels/zhutong.api.json",
        instanceJson);                       // 实例配置（凭证）
SmsResult r = ch.sendMessage("13800138000", "SMS_100", Map.of("code", "1234"));
if (r.isSuccess()) { ... }                   // r.getSmsId() / r.getData()
```

实例配置（密钥只在部署侧，不进描述文件）：

```json
{
  "schema": "hioas-instance/1.0",
  "channel": "zhutong",
  "configId": "zhutong-main",
  "config": { "accessKeyId": "...", "accessKeySecret": "...", "signature": "...", "templateId": "...", "templateName": "code" },
  "behavior": { "maxRetries": 2 }
}
```

## 新增渠道流程（零代码）

1. 照 `docs/02-hioas-api-标准-v1.0.md` 写 `<channel>.api.json`（声明 config 字段 → derive 签名链 → request 模板 → response 判定）；
2. 放入 `channels/`，加载时自动完成结构 + 语义 + 引用闭环校验；
3. 部署侧提供实例配置。

复杂签名无需写代码：阿里云 POP、腾讯云 TC3 均已用纯派生链表达并通过与独立实现的签名交叉校验（`integration/AliyunSignatureTest`、`TencentSignatureTest`）。

## 设计决策（2026-08-28 评审确认）

| 决策 | 选择 |
|------|------|
| 签名机制 | 纯派生链 + 原语函数，不内置厂商签名策略库 |
| 表达式引擎 | 自研轻量实现，无第三方表达式库依赖 |
| 项目形态 | 独立项目；门面方法对齐 `SmsBlend`、返回对齐 `SmsResponse`，但不依赖 SMS4J |
| SDK 类渠道 | `protocol: java` SPI 适配器兜底（唯一允许写代码的扩展点） |

## 构建与测试

```bash
mvn test        # 62 tests
```

依赖仅 `jackson-databind` + `slf4j-api`（运行时），Java 17+。

## 联调测试用例（docs/examples）

IntelliJ HTTP Client 联调用例，覆盖全部 19 个内置渠道（27 个请求），有两份：

### sms.http —— 真实厂商接口

面向真实厂商接口，覆盖全部 19 个内置渠道（27 个请求）：

- `http-client.private.env.json` 填真实凭证（已列入 `.gitignore`，勿提交），`http-client.env.json` 为公共变量；
- 动态签名渠道（助通/阿里/腾讯/网易/容联/MAS/赛邮/联麓/梦网/亿美/旦米等）内嵌 pre-request 脚本实时计算签名与时间戳，直接发送即可（需 IntelliJ IDEA 2024.2+）；
- 官方 crypto API 仅文档化 sha256 系列，md5 / sha1 / hmac-sha1 采用内联纯 JS 实现，已与 `node:crypto` 逐字节对照验证；
- 每个请求与 `channels/<channel>.api.json` 描述文件一一对应，可用于校验描述文件的请求形态。

### sms-mock.http —— 本地 Mock（无需任何真实凭证，可直接点击测试）

把所有渠道 URL 指向随项目提供的本地 mock 服务器，环境选 `mock` 即可逐条点击测试：

1. 启动 mock 服务器（`src/test/java/com/hioas/sms/mock/MockVendorServer.java`，默认端口 18080）：
   - IDEA：打开该文件右键 Run `main()`；或命令行
     `mvn test-compile && java -cp target/classes:target/test-classes com.hioas.sms.mock.MockVendorServer`；
2. HTTP Client 面板右上角环境选择 `mock`（定义在 `http-client.env.json`，全部为 `localhost:18080` 地址 + `mock-*` 占位凭证）；
3. 逐条点击 ▶。每个请求下方都带 `> {% client.test(...) %}` 断言块（即各渠道 `response.successWhen`），
   成功 ✅ / 失败 ❌ 直接显示在请求旁；
4. mock 会校验各渠道必填字段，缺失返回 400 + `{"mockError": ...}`（断言变红），便于定位请求形态问题；
   签名/时间戳由简化脚本填占位值（mock 不校验签名，只校验字段齐全）。

## 验证状态

- ✅ 62 个单元测试/集成测试全部通过
- ✅ 19 个内置描述文件全部通过 Meta-Schema 机器校验 + 引擎语义校验
- ✅ 19 渠道全量冒烟：加载 → 校验 → 路由 → 渲染 → 发送全链路跑通（`AllChannelsSmokeTest`）
- ✅ 签名交叉校验（与独立实现逐字节一致）：助通双重 MD5、阿里 POP、腾讯 TC3、联麓、移动云MAS、赛邮、网易
- ✅ `sms.http` 全部 22 个 pre-request 脚本经 Node 沙箱执行并与 `node:crypto` 独立重算逐字节对照通过
- ⏳ 未做真实厂商联调（需真实凭证）——`docs/examples/sms.http` 已备好 19 渠道直发用例，填入凭证即可逐一验证；不想填凭证时可用 `docs/examples/sms-mock.http` + `MockVendorServer`（本地 mock）在 IDEA 中逐条点击验证请求形态与 successWhen 判定。

## 标准增补（v1.0.1）

批量接入 A/B 级渠道时对标准做了向后兼容的小扩展，详见标准文档 §16：
`ensurePrefix` 函数、`kvJoin` 模板语义、`body.charset`、响应字段表达式化、`operation.inputs`。
