package aw.httphandler;

import aw.auth.ClientAuth;
import aw.config.ConfigLoader.ProxyConfig;
import aw.config.ConfigLoader.ProxyConfig.Provider;
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
 * POST /v1/responses
 * OpenAI Responses API
 */
public class OpenAIResponseHttpHandler {

  private static final Logger log = LoggerFactory.getLogger(OpenAIResponseHttpHandler.class);

  /** 落库用的来源接口标识 */
  private static final String API = "openai.responses";

  /** 上游调用共享 client：连接超时 30s；请求级超时 300s 只覆盖到响应头，body 读取由 withReadWatchdog 空闲超时兜底 */
  private static final HttpClient client = UpstreamHttpClient.SHARED;

  private final ConversationStore conversationStore;
  private final ProxyConfig proxyConfig;

  public OpenAIResponseHttpHandler(ConversationStore conversationStore, ProxyConfig proxyConfig) {
    this.conversationStore = conversationStore;
    this.proxyConfig = proxyConfig;
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

    String requestBody = ctx.body();

    log.info("Handling request body: {}", requestBody);

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

    ProxyConfig.Model modelConfig = proxyConfig.models().stream().filter(m -> m.name().equals(model)).findFirst().orElse(null);
    if (modelConfig == null) {
      ctx.status(400).result("Invalid model");
      return;
    }

    Provider provider = proxyConfig.providers().stream()
        .filter(it -> modelConfig.provider().equals(it.name())).findFirst().orElse(null);
    if (provider == null) {
      ctx.status(400).result("Invalid provider");
      return;
    }

    jsonObject.put("model", modelConfig.upstream());

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
      chatMessages = parseMessages(jsonObject);

      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(provider.openaiBaseUrl() + "/responses"))
          .timeout(java.time.Duration.ofSeconds(300))
          .header("Authorization", "Bearer " + provider.apiKey())
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(jsonObject.toJSONString()))
          .build();

      // 拿到响应头即返回，body 通过 InputStream 持续读取
      log.info("client send");
      HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
      statusCode = response.statusCode();
      log.info("Response status code: {}", statusCode);
      ctx.status(statusCode);

      String contentType = response.headers().firstValue("Content-Type").orElse("application/json");
      ctx.contentType(contentType);
      ctx.header("Cache-Control", "no-cache");

      if (contentType.contains("text/event-stream")) {
        // 逐块把上游 SSE 内容写回客户端，每块 flush 保证及时下发。
        // 注意：必须用 ctx.res().getOutputStream()（Jetty 原生流，flush 会立即提交 chunk），
        // 不能用 ctx.outputStream()——其 CompressedOutputStream 未重写 flush()，
        // 调用 flush() 是空操作，响应会被缓冲到 handler 结束才一次性发出。
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        // 空闲读超时：上游停止吐数据 300s 后由看门狗 close 流，防止半截挂死永久占用请求线程
        try (InputStream upstream = UpstreamHttpClient.withReadWatchdog(response.body(), java.time.Duration.ofSeconds(300));
             OutputStream output = ctx.res().getOutputStream()) {
          byte[] buffer = new byte[8192];
          int read;
          while ((read = upstream.read(buffer)) != -1) {
            log.info("server send sse {}, read {}, {}", buffer.length, read, new String(buffer, 0, read));
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
        try (InputStream in = UpstreamHttpClient.withReadWatchdog(response.body(), java.time.Duration.ofSeconds(300))) {
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
      conversationStore.log(API, sessionId, clientApiKey, model, modelConfig.provider(), stream, statusCode,
          requestBody, responseText,
          usage == null ? null : usage.promptTokens(),
          usage == null ? null : usage.completionTokens(),
          usage == null ? null : usage.totalTokens(),
          usage == null ? null : usage.cacheTokens(),
          System.currentTimeMillis() - start, errorMessage, chatMessages);
    }
  }

  /** 把 Responses API 的 input 展开成待入库的消息列表：字符串存为单条 user 消息；数组按 message 项展开，content 分块拼接其 text 字段 */
  private static List<ConversationStore.ChatMessage> parseMessages(JSONObject jsonObject) {
    List<ConversationStore.ChatMessage> list = new ArrayList<>();
    Object input = jsonObject.get("input");
    if (input instanceof String s) {
      list.add(new ConversationStore.ChatMessage(0, "user", s));
      return list;
    }
    if (!(input instanceof JSONArray items)) {
      return list;
    }
    for (int i = 0; i < items.size(); i++) {
      if (!(items.get(i) instanceof JSONObject m)) {
        // 非对象元素（畸形请求）跳过，不影响转发
        continue;
      }
      if (m.getString("role") == null) {
        // 非消息项（如 item_reference / function_call_output）跳过
        continue;
      }
      Object content = m.get("content");
      String contentText;
      if (content instanceof String str) {
        contentText = str;
      } else if (content instanceof JSONArray parts) {
        StringBuilder sb = new StringBuilder();
        for (int j = 0; j < parts.size(); j++) {
          JSONObject part = parts.getJSONObject(j);
          String text = part != null ? part.getString("text") : null;
          if (text != null) {
            if (sb.length() > 0) {
              sb.append('\n');
            }
            sb.append(text);
          }
        }
        contentText = sb.length() > 0 ? sb.toString() : JSON.toJSONString(content);
      } else {
        contentText = content == null ? null : JSON.toJSONString(content);
      }
      list.add(new ConversationStore.ChatMessage(i, m.getString("role"), contentText));
    }
    return list;
  }

  /** 非流式响应：直接解析 usage 字段（Responses API 字段为 input_tokens / output_tokens / total_tokens） */
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

  /** cache_tokens 取 input_tokens_details.cached_tokens（命中缓存的输入 token 数） */
  private static Usage toUsage(JSONObject usageJson) {
    JSONObject details = usageJson.getJSONObject("input_tokens_details");
    Integer cached = details != null ? details.getInteger("cached_tokens") : null;
    return new Usage(usageJson.getInteger("input_tokens"),
        usageJson.getInteger("output_tokens"), usageJson.getInteger("total_tokens"), cached);
  }

  /** 流式响应：usage 在 response.completed 事件的 response.usage 里（Responses API 流式无需 stream_options） */
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
        JSONObject event = JSON.parseObject(payload);
        JSONObject response = event.getJSONObject("response");
        JSONObject usageJson = response != null ? response.getJSONObject("usage") : null;
        if (usageJson == null) {
          // 兼容部分网关把 usage 放在事件顶层
          usageJson = event.getJSONObject("usage");
        }
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
