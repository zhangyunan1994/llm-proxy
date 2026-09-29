package aw.httphandler;

import aw.auth.ClientAuth;
import aw.config.ApiType;
import aw.config.ProxyConfig;
import aw.config.ProxyConfig.Provider;
import aw.db.ConversationStore;
import aw.util.StringUtils;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.javalin.http.Context;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * POST /v1/chat/completions
 */
public class OpenAIChatCompletionsHttpHandler {

  private static final Logger log = LoggerFactory.getLogger(OpenAIChatCompletionsHttpHandler.class);

  /** 落库用的来源接口标识 */
  private static final String API = "openai.chat.completions";

  /** 上游共享 client（Server 注入，连接超时见 server.connect_timeout_seconds） */
  private final HttpClient client;

  private final ConversationStore conversationStore;
  private final ProxyConfig proxyConfig;

  public OpenAIChatCompletionsHttpHandler(ConversationStore conversationStore, ProxyConfig proxyConfig, HttpClient upstreamClient) {
    this.conversationStore = conversationStore;
    this.proxyConfig = proxyConfig;
    this.client = upstreamClient;
  }

  /** 上游响应里的 usage（流式响应从 SSE 分片中尽力提取） */
  private record Usage(Integer promptTokens, Integer completionTokens, Integer totalTokens, Integer cacheTokens) {}

  public void handle(Context ctx) throws IOException, InterruptedException {
    log.info("Handling request content length: {}", ctx.contentLength());
    log.info("Handling request content: {}", ctx.contentType());

    if (ctx.contentType() == null || !ctx.contentType().contains("application/json")) {
      ctx.status(400).result("Invalid request");
      return;
    }

    if (ctx.contentLength() > UpstreamHttpClient.MAX_BODY_BYTES) {
      ctx.status(413).result("Payload too large");
      return;
    }

    String requestBody = ctx.body();

    log.debug("Handling request body: {}", requestBody);

    if (!JSON.isValidObject(requestBody)) {
      ctx.status(400).result("Invalid request");
      return;
    }

    JSONObject jsonObject = JSON.parseObject(requestBody);

    String model = jsonObject.getString("model");

    if (StringUtils.isBlank(model)) {
      ctx.status(400).result("Invalid model");
      return;
    }

    ProxyConfig.Route route = proxyConfig.findModelAndProvider(model, ApiType.OPENAI_CHAT_COMPLETIONS);

    if (route == null) {
      ctx.status(400).result("Invalid model or provider");
      return;
    }

    Provider provider = route.provider();

    jsonObject.put("model", route.upstreamModel());

    String sessionId = ctx.header("X-Session-Id");
    String clientApiKey = ctx.attribute(ClientAuth.CLIENT_KEY_ATTR);

    long start = System.currentTimeMillis();
    boolean stream = false;
    Integer statusCode = null;
    String responseText = null;
    Usage usage = null;
    String errorMessage = null;
    List<ConversationStore.ChatMessage> chatMessages = null;
    try {
      // 解析消息、构造上游请求都放在审计保护范围内：畸形请求（如 base_url 含非法字符）不能击穿审计
      stream = Boolean.TRUE.equals(jsonObject.getBoolean("stream"));
      if (stream) {
        // 代理层注入 include_usage: 否则上游默认不发 usage 分片，流式审计拿不到 token 用量。
        // stream_options 类型非法（非对象）时直接覆盖——注入失败比请求被拒更糟
        Object existing = jsonObject.get("stream_options");
        JSONObject streamOptions = existing instanceof JSONObject o ? o : new JSONObject();
        streamOptions.put("include_usage", true);
        jsonObject.put("stream_options", streamOptions);
      }
      chatMessages = parseMessages(jsonObject);

      HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
          .uri(URI.create(provider.getOpenaiBaseUrl() + "/chat/completions"))
          .timeout(java.time.Duration.ofSeconds(proxyConfig.getServer().getRequestTimeoutSeconds()))
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(jsonObject.toJSONString()));
      // api_key 为空则不发 Authorization（本地自建/免 key 网关）；配置校验保证厂商直连必有 key
      if (!StringUtils.isBlank(provider.getApiKey())) {
        requestBuilder.header("Authorization", "Bearer " + provider.getApiKey());
      }
      UpstreamHttpClient.forwardClientHeaders(ctx, requestBuilder);
      HttpRequest request = requestBuilder.build();

      // 拿到响应头即返回，body 通过 InputStream 持续读取
      log.info("client send");
      HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
      statusCode = response.statusCode();
      log.info("Response status code: {}", statusCode);
      ctx.status(statusCode);
      // 上游响应头透传（限流/请求 ID 等），随后显式设置的 Content-Type / Cache-Control 覆盖透传值
      UpstreamHttpClient.passThroughHeaders(response, ctx);

      String contentType = response.headers().firstValue("Content-Type").orElse("application/json");
      ctx.contentType(contentType);
      ctx.header("Cache-Control", "no-cache");

      java.time.Duration idleTimeout = java.time.Duration.ofSeconds(proxyConfig.getServer().getReadIdleTimeoutSeconds());
      if (contentType.contains("text/event-stream")) {
        // 逐块把上游 SSE 内容写回客户端，每块 flush 保证及时下发。
        // 注意：必须用 ctx.res().getOutputStream()（Jetty 原生流，flush 会立即提交 chunk），
        // 不能用 ctx.outputStream()——其 CompressedOutputStream 未重写 flush()，
        // 调用 flush() 是空操作，响应会被缓冲到 handler 结束才一次性发出。
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        // 空闲读超时：上游停止吐数据 300s 后由看门狗 close 流，防止半截挂死永久占用请求线程
        try (InputStream upstream = UpstreamHttpClient.withReadWatchdog(response.body(), idleTimeout);
             OutputStream output = ctx.res().getOutputStream()) {
          byte[] buffer = new byte[8192];
          int read;
          while ((read = upstream.read(buffer)) != -1) {
            log.debug("server send sse {}, read {}, {}", buffer.length, read, new String(buffer, 0, read));
            output.write(buffer, 0, read);
            output.flush();
            captured.write(buffer, 0, read);
          }
        } finally {
          // 正常结束或流被看门狗中断，都保留已转发的（半截）内容供审计
          responseText = captured.toString(StandardCharsets.UTF_8);
        }
        usage = extractStreamUsage(responseText);
      }
      else {
        byte[] body;
        try (InputStream in = UpstreamHttpClient.withReadWatchdog(response.body(), idleTimeout)) {
          body = in.readAllBytes();
        }
        ctx.result(new ByteArrayInputStream(body));
        responseText = new String(body, StandardCharsets.UTF_8);
        usage = extractJsonUsage(responseText);
      }
    }
    catch (IOException | InterruptedException e) {
      // 转发或回写失败也要留痕（statusCode 可能为 null）
      errorMessage = e.toString();
      throw e;
    }
    catch (RuntimeException e) {
      // 解析/构造阶段的意外异常（如 base_url 含非法字符、畸形消息）：500 且必须留痕审计
      errorMessage = e.toString();
      ctx.status(500).result("Internal Server Error");
    }
    finally {
      // 无论成功失败都落库；conversationStore 内部吞掉 DB 异常，不影响转发
      conversationStore.log(API, sessionId, clientApiKey, model, provider.getName(), stream, statusCode,
          requestBody, responseText,
          usage == null ? null : usage.promptTokens(),
          usage == null ? null : usage.completionTokens(),
          usage == null ? null : usage.totalTokens(),
          usage == null ? null : usage.cacheTokens(),
          System.currentTimeMillis() - start, errorMessage, chatMessages);
    }
  }

  /** 把请求 messages 数组展开成待入库的消息列表；content 是数组（多模态）时存其 JSON 字符串。
   *  畸形元素（非对象）跳过不影响转发；缺 role 存 "unknown"——库表 role NOT NULL，不能让畸形消息炸掉整条审计 */
  static List<ConversationStore.ChatMessage> parseMessages(JSONObject jsonObject) {
    List<ConversationStore.ChatMessage> list = new ArrayList<>();
    JSONArray messages = jsonObject.getJSONArray("messages");
    if (messages == null) {
      return list;
    }
    for (int i = 0; i < messages.size(); i++) {
      if (!(messages.get(i) instanceof JSONObject m)) {
        continue;
      }
      Object content = m.get("content");
      String contentText = content instanceof String s ? s : content == null ? null : JSON.toJSONString(content);
      String role = m.getString("role");
      list.add(new ConversationStore.ChatMessage(i, role == null ? "unknown" : role, contentText));
    }
    return list;
  }

  /** 非流式响应：直接解析 usage 字段 */
  private static Usage extractJsonUsage(String responseBody) {
    try {
      JSONObject usage = JSON.parseObject(responseBody).getJSONObject("usage");
      if (usage == null) {
        return null;
      }
      return toUsage(usage);
    } catch (Exception e) {
      log.debug("解析响应 usage 失败", e);
      return null;
    }
  }

  /** cache_tokens 取 prompt_tokens_details.cached_tokens（命中缓存的输入 token 数） */
  private static Usage toUsage(JSONObject usageJson) {
    JSONObject details = usageJson.getJSONObject("prompt_tokens_details");
    Integer cached = details != null ? details.getInteger("cached_tokens") : null;
    return new Usage(usageJson.getInteger("prompt_tokens"),
        usageJson.getInteger("completion_tokens"), usageJson.getInteger("total_tokens"), cached);
  }

  /** 流式响应：扫描 SSE data 行，取最后一个带 usage 的分片（需 stream_options.include_usage） */
  private static Usage extractStreamUsage(String sse) {
    Usage usage = null;
    for (String line : sse.split("\n")) {
      line = line.trim();
      if (!line.startsWith("data:")) {
        continue;
      }
      String payload = line.substring(5).trim();
      if (payload.isEmpty() || "[DONE]".equals(payload)) {
        continue;
      }
      try {
        JSONObject usageJson = JSON.parseObject(payload).getJSONObject("usage");
        if (usageJson != null) {
          usage = toUsage(usageJson);
        }
      } catch (Exception ignore) {
        // 非 JSON 行直接跳过
      }
    }
    return usage;
  }
}
