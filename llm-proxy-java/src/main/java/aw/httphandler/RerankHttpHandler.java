package aw.httphandler;

import aw.config.ConfigLoader.ProxyConfig;
import aw.config.ConfigLoader.ProxyConfig.Provider;
import aw.db.ConversationStore;
import aw.util.StringUtils;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import io.javalin.http.Context;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * POST /v1/rerank => Rank documents against a query — requires a reranker model
 */
public class RerankHttpHandler {

  private static final Logger log = LoggerFactory.getLogger(RerankHttpHandler.class);

  /** 落库用的来源接口标识 */
  private static final String API = "rerank";

  HttpClient client = HttpClient.newHttpClient();

  private final ConversationStore conversationStore;
  private final ProxyConfig proxyConfig;

  public RerankHttpHandler(ConversationStore conversationStore, ProxyConfig proxyConfig) {
    this.conversationStore = conversationStore;
    this.proxyConfig = proxyConfig;
  }

  /** 上游响应里的 usage */
  private record Usage(Integer promptTokens, Integer completionTokens, Integer totalTokens, Integer cacheTokens) {}

  public void handle(Context ctx) throws IOException, InterruptedException {
    log.info("Handling request content length: {}", ctx.contentLength());
    log.info("Handling request content: {}", ctx.contentType());

    if (ctx.contentLength() < 10 || ctx.contentType() == null || !ctx.contentType().contains("application/json")) {
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

    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(provider.openaiBaseUrl() + "/rerank"))
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
      log.info("client send");
      HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
      statusCode = response.statusCode();
      log.info("Response status code: {}", statusCode);
      String contentType = response.headers().firstValue("Content-Type").orElse("application/json");
      ctx.contentType(contentType);
      byte[] body = response.body().readAllBytes();
      ctx.status(statusCode).result(new ByteArrayInputStream(body));
      responseText = new String(body, StandardCharsets.UTF_8);
      usage = extractJsonUsage(responseText);
    }
    catch (Exception e) {
      errorMessage = e.toString();
      ctx.status(500).result("Internal Server Error");
    }
    finally {
      // 无论成功失败都落库；conversationStore 内部吞掉 DB 异常，不影响转发
      conversationStore.log(API, sessionId, model, false, statusCode, requestBody, responseText,
          usage == null ? null : usage.promptTokens(),
          usage == null ? null : usage.completionTokens(),
          usage == null ? null : usage.totalTokens(),
          usage == null ? null : usage.cacheTokens(),
          System.currentTimeMillis() - start, errorMessage, null);
    }
  }

  /** 非流式响应：rerank 的 usage 各家不一，尽力提取——Cohere/SiliconFlow 在 meta.billed_units（或 meta.tokens），Jina 在 usage.total_tokens */
  private static Usage extractJsonUsage(String responseBody) {
    try {
      JSONObject root = JSON.parseObject(responseBody);
      JSONObject meta = root.getJSONObject("meta");
      JSONObject units = null;
      if (meta != null) {
        units = meta.getJSONObject("billed_units");
        if (units == null) {
          units = meta.getJSONObject("tokens");
        }
      }
      if (units != null) {
        return new Usage(units.getInteger("input_tokens"), units.getInteger("output_tokens"), null, null);
      }
      JSONObject usage = root.getJSONObject("usage");
      if (usage != null) {
        Integer input = usage.getInteger("input_tokens");
        Integer total = usage.getInteger("total_tokens");
        return new Usage(input != null ? input : total, usage.getInteger("output_tokens"), total, null);
      }
      return null;
    } catch (Exception e) {
      log.debug("解析响应 usage 失败", e);
      return null;
    }
  }
}
