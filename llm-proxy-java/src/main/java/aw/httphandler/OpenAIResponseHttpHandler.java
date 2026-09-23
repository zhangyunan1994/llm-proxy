package aw.httphandler;

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

  HttpClient client = HttpClient.newHttpClient();

  private final ConversationStore conversationStore;
  private final ProxyConfig proxyConfig;

  public OpenAIResponseHttpHandler(ConversationStore conversationStore, ProxyConfig proxyConfig) {
    this.conversationStore = conversationStore;
    this.proxyConfig = proxyConfig;
  }

  /** 上游响应里的 usage（流式响应从 SSE 分片中尽力提取） */
  private record Usage(Integer promptTokens, Integer completionTokens, Integer totalTokens) {}

  public void handle(Context ctx) throws IOException, InterruptedException {
    log.info("Handling request content length: {}", ctx.contentLength());
    log.info("Handling request content: {}", ctx.contentType());

    if (ctx.contentLength() < 55 || ctx.contentType() == null || !ctx.contentType().contains("application/json")) {
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

    boolean stream = Boolean.TRUE.equals(jsonObject.getBoolean("stream"));
    String sessionId = ctx.header("X-Session-Id");
    List<ConversationStore.ChatMessage> chatMessages = parseMessages(jsonObject);

    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(provider.openaiBaseUrl() + "/responses"))
        .header("Authorization", "Bearer " + provider.apiKey())
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(jsonObject.toJSONString()))
        .build();

    long start = System.currentTimeMillis();
    Integer statusCode = null;
    String responseText = null;
    Usage usage = null;
    String errorMessage = null;
    try {
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
        try (InputStream upstream = response.body(); OutputStream output = ctx.res().getOutputStream()) {
          byte[] buffer = new byte[8192];
          int read;
          while ((read = upstream.read(buffer)) != -1) {
            log.info("server send sse {}, read {}, {}", buffer.length, read, new String(buffer, 0, read));
            output.write(buffer, 0, read);
            output.flush();
            captured.write(buffer, 0, read);
          }
        }
        responseText = captured.toString(StandardCharsets.UTF_8);
        usage = extractStreamUsage(responseText);
      }
      else {
        byte[] body = response.body().readAllBytes();
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
    finally {
      // 无论成功失败都落库；conversationStore 内部吞掉 DB 异常，不影响转发
      conversationStore.log(sessionId, model, stream, statusCode, requestBody, responseText,
          usage == null ? null : usage.promptTokens(),
          usage == null ? null : usage.completionTokens(),
          usage == null ? null : usage.totalTokens(),
          System.currentTimeMillis() - start, errorMessage, chatMessages);
    }
  }

  /** 把请求 messages 数组展开成待入库的消息列表；content 是数组（多模态）时存其 JSON 字符串 */
  private static List<ConversationStore.ChatMessage> parseMessages(JSONObject jsonObject) {
    List<ConversationStore.ChatMessage> list = new ArrayList<>();
    JSONArray messages = jsonObject.getJSONArray("messages");
    if (messages == null) {
      return list;
    }
    for (int i = 0; i < messages.size(); i++) {
      JSONObject m = messages.getJSONObject(i);
      if (m == null) {
        continue;
      }
      Object content = m.get("content");
      String contentText = content instanceof String s ? s : content == null ? null : JSON.toJSONString(content);
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
      return new Usage(usage.getInteger("input_tokens"),
          usage.getInteger("output_tokens"), usage.getInteger("total_tokens"));
    } catch (Exception e) {
      log.debug("解析响应 usage 失败", e);
      return null;
    }
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
          usage = new Usage(usageJson.getInteger("input_tokens"),
              usageJson.getInteger("output_tokens"), usageJson.getInteger("total_tokens"));
        }
      } catch (Exception ignore) {
        // 非 JSON 行直接跳过
      }
    }
    return usage;
  }
}
