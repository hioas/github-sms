package com.hioas.sms.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * 本地 mock 短信厂商服务器 —— 供 docs/examples/sms-mock.http 在 IDEA 中直接点击测试。
 *
 * <p>为什么需要它：sms.http 面向真实厂商接口，需要真实凭证才能跑通；本服务器按
 * {@code src/main/resources/channels/<channel>.api.json} 的路径与 {@code successWhen} 判定规则，
 * 在本地返回「可判定为成功」的响应，从而无需任何真实凭证即可验证每个渠道的
 * 请求形态、签名占位渲染与响应解析。</p>
 *
 * <p>用法：</p>
 * <pre>
 *   方式一：IDEA 中打开本文件，右键 Run MockVendorServer.main()
 *   方式二：命令行 java -cp target/classes:target/test-classes com.hioas.sms.mock.MockVendorServer [port]
 *   默认端口 18080，可传参覆盖：MockVendorServer 18081
 * </pre>
 *
 * <p>启动后在 HTTP Client 面板选择 {@code mock} 环境，逐条点击 sms-mock.http 中的请求即可。
 * 每个请求都会校验必填字段：缺失时返回 400 + 失败体，对应请求下方的 client.test 断言会变红，
 * 便于定位「请求形态」问题。</p>
 */
public final class MockVendorServer {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String UTF_8 = "UTF-8";

    private final int port;
    private final List<Route> routes = new ArrayList<>();

    public MockVendorServer(int port) {
        this.port = port;
        registerRoutes();
    }

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 18080;
        new MockVendorServer(port).start();
    }

    public void start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", new Dispatcher());
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        System.out.println("================================================================");
        System.out.println("[mock-vendor] 本地短信厂商 mock 服务器已启动");
        System.out.println("[mock-vendor]   http://localhost:" + port);
        System.out.println("[mock-vendor]   在 HTTP Client 面板选择 mock 环境后打开 sms-mock.http");
        System.out.println("================================================================");
        for (Route r : routes) {
            System.out.printf("[mock-vendor]   %-5s %s   -> %s%n", r.method, r.path, r.channel);
        }
    }

    // ------------------------------------------------------------------ routes

    private void registerRoutes() {
        // 1. 创蓝 chuanglan —— successWhen: $.code == '0'
        add("POST", "/msg/msg/variable/json", "chuanglan",
                req -> require(req.json, "account", "password", "msg", "params"),
                "{\"code\":\"0\",\"msgid\":\"mock-chuanglan\",\"error\":\"0\",\"msg\":\"提交成功\"}");

        // 2. 助通 zhutong —— successWhen: $.code <= 200
        add("POST", "/v2/sendSmsTp", "zhutong",
                req -> require(req.json, "username", "password", "tKey", "tpId", "records"),
                "{\"code\":200,\"msgid\":\"mock-zhutong-tpl\",\"data\":\"ok\"}");
        add("POST", "/v2/sendSms", "zhutong",
                req -> require(req.json, "username", "password", "tKey", "mobile", "content"),
                "{\"code\":200,\"msgid\":\"mock-zhutong-custom\",\"data\":\"ok\"}");

        // 3. 腾讯云 tencent —— 路径为 "/"，header X-TC-Action: SendSms；successWhen: $.Response.SendStatusSet[0].Code == 'Ok'
        //    必须先于 aliyun 注册：两者都 POST 到 "/"，用 X-TC-Action 头区分
        add("POST", "/", "tencent",
                req -> require(req.json, "PhoneNumberSet", "SmsSdkAppId", "SignName", "TemplateId"),
                "{\"Response\":{\"SendStatusSet\":[{\"SerialNo\":\"mock-tencent\",\"PhoneNumber\":\"13800138000\","
                        + "\"Fee\":1,\"SessionContext\":\"\",\"Code\":\"Ok\",\"Message\":\"send success\",\"IsoCode\":\"CN\"}],"
                        + "\"RequestId\":\"mock-tencent-req\"}}",
                "application/json; charset=utf-8", false, Map.of("X-TC-Action", "SendSms"));

        // 4. 阿里云 aliyun —— 路径为 "/" + query，无 X-TC-Action 头即命中；successWhen: $.Code == 'OK'
        add("POST", "/", "aliyun",
                req -> require(req.form, "PhoneNumbers", "SignName", "TemplateParam", "TemplateCode"),
                "{\"Code\":\"OK\",\"Message\":\"OK\",\"RequestId\":\"mock-aliyun\",\"BizId\":\"mock-aliyun-biz\"}",
                "application/json; charset=utf-8", false, Map.of());

        // 5. 云片 yunpian —— successWhen: $.code == 0
        add("POST", "/v2/sms/tpl_single_send.json", "yunpian",
                req -> require(req.form, "apikey", "mobile", "tpl_id", "tpl_value"),
                "{\"code\":0,\"msg\":\"OK\",\"count\":1,\"fee\":0.05,\"unit\":\"RMB\","
                        + "\"mobile\":\"13800138000\",\"sid\":\"mock-yunpian\"}");

        // 6. 网易云信 netease —— successWhen: $.code <= 200
        add("POST", "/sms/sendTemplateSMS.action", "netease",
                req -> concat(
                        require(req.headers, "AppKey", "Nonce", "CurTime", "CheckSum"),
                        require(req.form, "templateid", "mobiles", "params")),
                "{\"code\":200,\"msg\":\"ok\",\"obj\":{\"msgId\":\"mock-netease\",\"code\":\"1\","
                        + "\"templateId\":\"1\",\"sendStatus\":1}}");

        // 7. 容联云 cloopen —— 路径含 sid，正则匹配；successWhen: $.statusCode == '000000'
        addRegex("POST", Pattern.compile("^/2013-12-26/Accounts/[^/]+/SMS/TemplateSMS$"), "cloopen",
                req -> concat(
                        require(req.headers, "Authorization"),
                        require(req.json, "to", "appId", "templateId", "datas")),
                "{\"statusCode\":\"000000\",\"statusMsg\":\"成功\",\"templateSMS\":{"
                        + "\"smsMessageSid\":\"mock-cloopen\",\"dateCreated\":\"20260831120000\"}}");

        // 8. 移动云MAS mas —— 整体 JSON Base64 后作为请求体；successWhen: $.rspcod == 'success' && $.success == true
        add("POST", "/sms/tmpsubmit", "mas",
                req -> require(req.json, "ecName", "apId", "secretKey", "templateId", "mobiles", "params", "mac"),
                "{\"rspcod\":\"success\",\"success\":true,\"rspmsg\":\"ok\",\"msgGroup\":\"mock-mas\"}",
                true);
        add("POST", "/sms/norsubmit", "mas",
                req -> require(req.json, "ecName", "apId", "secretKey", "mobiles", "content", "mac"),
                "{\"rspcod\":\"success\",\"success\":true,\"rspmsg\":\"ok\",\"msgGroup\":\"mock-mas\"}",
                true);

        // 9. 赛邮 submail —— 前置 /service/timestamp + send/xsend
        add("GET", "/service/timestamp", "submail",
                req -> new String[0],
                "{\"timestamp\":1700000000}");
        add("POST", "/sms/send.json", "submail",
                req -> require(req.json, "appid", "to", "content", "timestamp", "signature"),
                "{\"status\":\"success\",\"send_id\":\"mock-submail-send\",\"fee\":1,\"credits\":1}");
        add("POST", "/sms/xsend.json", "submail",
                req -> require(req.json, "appid", "to", "project", "vars", "timestamp", "signature"),
                "{\"status\":\"success\",\"send_id\":\"mock-submail-xsend\",\"fee\":1,\"credits\":1}");

        // 10. 联麓 lianlu —— successWhen: $.status == '00'
        add("POST", "/sms/trade/template/send", "lianlu",
                req -> require(req.json, "MchId", "AppId", "TimeStamp", "Signature", "PhoneNumberSet"),
                "{\"status\":\"00\",\"msg\":\"成功\",\"taskId\":\"mock-lianlu\"}");

        // 11. 梦网 montnets —— successWhen: $.result == '0'
        add("POST", "/v2/std/tmpl_send", "montnets",
                req -> require(req.json, "userid", "pwd", "timestamp", "mobile", "content", "tmplid"),
                "{\"result\":\"0\",\"msgid\":\"mock-montnets\",\"errmsg\":\"成功\"}");

        // 12. 亿美 emay —— 参数全部走 URL 查询串；successWhen: lower($.code) == 'success'
        add("POST", "/inter/sendSingleSMS", "emay",
                req -> require(req.query, "appId", "timestamp", "sign", "mobiles", "content"),
                "{\"code\":\"success\",\"smsId\":\"mock-emay\"}");

        // 13. 互亿 huyi —— GET 提交；successWhen: $.code == '2'
        add("GET", "/webservice/sms.php", "huyi",
                req -> require(req.query, "method", "account", "password", "content", "mobile"),
                "{\"code\":\"2\",\"msg\":\"提交成功\",\"smsid\":\"mock-huyi\"}");

        // 14. 螺丝帽 luosimao —— Basic Auth；successWhen: $.error == 0
        add("POST", "/v1/send.json", "luosimao",
                req -> concat(
                        require(req.headers, "Authorization"),
                        require(req.form, "mobile", "message")),
                "{\"error\":0,\"msg\":\"ok\",\"id\":123}");
        add("GET", "/v1/status.json", "luosimao",
                req -> require(req.headers, "Authorization"),
                "{\"error\":0,\"msg\":\"ok\",\"balance\":\"100.00\",\"deposit\":\"100.00\"}");

        // 15. 极光 jg —— Basic Auth(appKey:masterSecret)；successWhen: $.msg_id != null / $.success_count != null
        add("POST", "/v1/messages", "jg",
                req -> concat(
                        require(req.headers, "Authorization"),
                        require(req.json, "mobile", "sign_id", "temp_id", "temp_para")),
                "{\"msg_id\":\"mock-jg-single\"}");
        add("POST", "/v1/messages/batch", "jg",
                req -> concat(
                        require(req.headers, "Authorization"),
                        require(req.json, "sign_id", "temp_id", "recipients")),
                "{\"success_count\":2,\"msg_id\":\"mock-jg-batch\"}");

        // 16. 一信通 yixintong —— 响应为文本，含 result=0& 即成功
        add("POST", "/sms/Api/Send.do", "yixintong",
                req -> require(req.form, "SpCode", "LoginName", "Password", "MessageContent", "UserNumber", "SerialNumber"),
                "result=0&msgid=mock-yixintong&desc=ok",
                "text/plain; charset=utf-8");

        // 17. 布丁云 budingyun —— successWhen: $.bool == true
        add("POST", "/Api/Sent", "budingyun",
                req -> require(req.form, "key", "to", "content"),
                "{\"bool\":true,\"msg\":\"ok\"}");

        // 18. 旦米 danmi —— successWhen: $.respCode == '00000'
        add("POST", "/distributor/sendSMS", "danmi",
                req -> require(req.json, "accountSid", "templateid", "smsContent", "to", "timestamp", "sig"),
                "{\"respCode\":\"00000\",\"respMessage\":\"成功\"}");
        add("POST", "/distributor/user/query", "danmi",
                req -> require(req.json, "accountSid", "timestamp", "sig"),
                "{\"respCode\":\"00000\",\"respMessage\":\"成功\",\"balance\":\"100.00\"}");

        // 19. 鼎众 dingzhong —— successWhen: $.resCode == '0'
        add("POST", "/Sms/SendSms", "dingzhong",
                req -> require(req.form, "cdkey", "password", "mobile", "msg"),
                "{\"resCode\":\"0\",\"resMessage\":\"成功\"}");
        add("POST", "/Sms/SendTemplateSms", "dingzhong",
                req -> require(req.form, "cdkey", "password", "mobile", "templateId", "msgParam"),
                "{\"resCode\":\"0\",\"resMessage\":\"成功\"}");
    }

    // ------------------------------------------------------------------ helpers

    private void add(String method, String path, String channel, Required required, String body) {
        add(method, path, channel, required, body, "application/json; charset=utf-8", false, null);
    }

    private void add(String method, String path, String channel, Required required, String body, boolean base64Body) {
        add(method, path, channel, required, body, "application/json; charset=utf-8", base64Body, null);
    }

    private void add(String method, String path, String channel, Required required, String body, String contentType) {
        add(method, path, channel, required, body, contentType, false, null);
    }

    private void add(String method, String path, String channel, Required required, String body, String contentType, boolean base64Body) {
        add(method, path, channel, required, body, contentType, base64Body, null);
    }

    private void add(String method, String path, String channel, Required required, String body, String contentType, boolean base64Body, Map<String, String> headerGate) {
        routes.add(new Route(method, path, channel, required, body, contentType, base64Body, headerGate));
    }

    private void addRegex(String method, Pattern path, String channel, Required required, String body) {
        routes.add(new RegexRoute(method, path, channel, required, body));
    }

    private static String[] require(Map<String, ?> map, String... keys) {
        List<String> missing = new ArrayList<>();
        for (String k : keys) {
            if (map == null || !map.containsKey(k)) {
                missing.add(k);
            }
        }
        return missing.toArray(new String[0]);
    }

    private static String[] concat(String[] a, String[] b) {
        String[] out = new String[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private final class Dispatcher implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();
            Request req = new Request(ex);

            System.out.println("----------------------------------------------------------------");
            System.out.printf("[mock-vendor] %s %s%s%n", method, path,
                    req.rawQuery.isEmpty() ? "" : "?" + req.rawQuery);

            Route route = null;
            for (Route r : routes) {
                if (r.matches(req)) {
                    route = r;
                    break;
                }
            }

            if (route == null) {
                respond(ex, 404, "{\"mockError\":\"no route for " + method + " " + path + "\"}",
                        "application/json; charset=utf-8");
                return;
            }

            String[] missing = route.required.missing(req);
            if (missing.length > 0) {
                System.out.println("[mock-vendor]   ✗ " + route.channel + " 缺失必填字段: "
                        + String.join(", ", missing));
                respond(ex, 400, "{\"mockError\":\"missing required field(s): " + String.join(", ", missing) + "\"}",
                        "application/json; charset=utf-8");
                return;
            }

            System.out.println("[mock-vendor]   ✓ " + route.channel + " -> 200 " + route.body);
            respond(ex, 200, route.body, route.contentType);
        }
    }

    private static void respond(HttpExchange ex, int status, String body, String contentType) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    /** 一次请求的解析结果：method / path / query / form / json / headers / rawQuery / rawBody。 */
    private static final class Request {
        final String method;
        final String path;
        final Map<String, String> query = new LinkedHashMap<>();
        final Map<String, String> form = new LinkedHashMap<>();
        final Map<String, String> headers = new LinkedHashMap<>();
        final Map<String, Object> json;
        final String rawQuery;
        final String rawBody;

        Request(HttpExchange ex) throws IOException {
            method = ex.getRequestMethod();
            path = ex.getRequestURI().getPath();
            ex.getRequestHeaders().forEach((k, v) -> headers.put(k, v.get(0)));
            String rawQuery0 = ex.getRequestURI().getRawQuery();
            rawQuery = rawQuery0 == null ? "" : rawQuery0;
            if (rawQuery0 != null) {
                parseKv(rawQuery0, query);
            }
            byte[] bodyBytes = ex.getRequestBody().readAllBytes();
            rawBody = new String(bodyBytes, StandardCharsets.UTF_8);
            if (!rawBody.isBlank()) {
                parseKv(rawBody, form);
            }
            json = parseJson(rawBody);
        }

        /** 供 Base64DecodingRequired 复用：以解码出的 json 替换原 json，其余字段不变。 */
        Request withJson(Map<String, Object> newJson) {
            return new Request(this, newJson);
        }

        private Request(Request other, Map<String, Object> newJson) {
            this.method = other.method;
            this.path = other.path;
            this.query.putAll(other.query);
            this.form.putAll(other.form);
            this.headers.putAll(other.headers);
            this.rawQuery = other.rawQuery;
            this.rawBody = other.rawBody;
            this.json = newJson;
        }

        /** 把一段文本当作 JSON 解析；空串/非 JSON 返回 null。 */
        @SuppressWarnings("unchecked")
        static Map<String, Object> parseJson(String s) {
            if (s == null || s.isBlank() || !s.trim().startsWith("{")) {
                return null;
            }
            try {
                return MAPPER.readValue(s, Map.class);
            } catch (Exception e) {
                return null;
            }
        }

        private void parseKv(String s, Map<String, String> target) {
            for (String pair : s.split("&")) {
                if (pair.isEmpty()) {
                    continue;
                }
                int idx = pair.indexOf('=');
                if (idx < 0) {
                    target.put(dec(pair), "");
                } else {
                    target.put(dec(pair.substring(0, idx)), dec(pair.substring(idx + 1)));
                }
            }
        }

        private String dec(String s) {
            try {
                return URLDecoder.decode(s, UTF_8);
            } catch (Exception e) {
                return s;
            }
        }
    }

    @FunctionalInterface
    private interface Required {
        String[] missing(Request req);
    }

    private static class Route {
        final String method;
        final String path;
        final String channel;
        final Required required;
        final String body;
        final String contentType;
        final Map<String, String> headerGate;

        Route(String method, String path, String channel, Required required, String body, String contentType, boolean base64Body, Map<String, String> headerGate) {
            this.method = method;
            this.path = path;
            this.channel = channel;
            this.required = base64Body ? new Base64DecodingRequired(required) : required;
            this.body = body;
            this.contentType = contentType;
            this.headerGate = headerGate;
        }

        boolean matches(Request req) {
            if (!method.equals(req.method) || !path.equals(req.path)) {
                return false;
            }
            if (headerGate != null) {
                for (Map.Entry<String, String> e : headerGate.entrySet()) {
                    if (!e.getValue().equals(req.headers.get(e.getKey()))) {
                        return false;
                    }
                }
            }
            return true;
        }
    }

    private static class RegexRoute extends Route {
        private final Pattern pattern;

        RegexRoute(String method, Pattern path, String channel, Required required, String body) {
            super(method, path.pattern(), channel, required, body, "application/json; charset=utf-8", false, null);
            this.pattern = path;
        }

        @Override
        boolean matches(Request req) {
            return method.equals(req.method) && pattern.matcher(req.path).matches();
        }
    }

    /** MAS 渠道请求体是「整体 JSON 的 Base64」（真实厂商约定，sms.http 中用 btoa(utf8(payload)) 生成）。 */
    private static final class Base64DecodingRequired implements Required {
        private final Required delegate;

        Base64DecodingRequired(Required delegate) {
            this.delegate = delegate;
        }

        @Override
        public String[] missing(Request req) {
            String raw = req.rawBody.trim();
            byte[] decoded = tryDecode(raw);
            if (decoded == null) {
                // 无法解码（http 用例里该变量未生成/不是 base64）→ 视为缺失全部字段，断言变红便于定位
                return delegate.missing(req.withJson(null));
            }
            Map<String, Object> json = Request.parseJson(new String(decoded, StandardCharsets.UTF_8));
            return delegate.missing(req.withJson(json));
        }

        /** 兼容标准/URL-Safe/MIME 换行三种 Base64 变体；解码失败返回 null。 */
        private static byte[] tryDecode(String raw) {
            String cleaned = raw.replaceAll("\\s+", "");
            if (cleaned.isEmpty()) {
                return null;
            }
            for (Base64.Decoder d : new Base64.Decoder[]{Base64.getDecoder(), Base64.getUrlDecoder()}) {
                try {
                    return d.decode(cleaned);
                } catch (IllegalArgumentException ignore) {
                    // 尝试下一种变体
                }
            }
            return null;
        }
    }
}
