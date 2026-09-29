package aw.httphandler;

import aw.auth.ClientAuth;
import aw.config.ApiType;
import aw.config.ProxyConfig;
import aw.config.ProxyConfig.Model;
import aw.config.ProxyConfig.Provider;
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
import org.yaml.snakeyaml.util.Tuple;

/**
 * POST /v1/rerank => Rank documents against a query — requires a reranker model
 */
public class RerankHttpHandler {

  private static final Logger log = LoggerFactory.getLogger(RerankHttpHandler.class);

  /** 落库用的来源接口标识 */
  private static final String API = "rerank";

  /** 上游共享 client（Server 注入，连接超时见 server.connect_timeout_seconds） */
  private final HttpClient client;

  private final ConversationStore conversationStore;
  private final ProxyConfig proxyConfig;

  public RerankHttpHandler(ConversationStore conversationStore, ProxyConfig proxyConfig, HttpClient upstreamClient) {
    this.conversationStore = conversationStore;
    this.proxyConfig = proxyConfig;
    this.client = upstreamClient;
  }

  /** 上游响应里的 usage */
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

    Tuple<Model, Provider> modelAndProvider = proxyConfig.findModelAndProvider(model, ApiType.RERANK);

    if (modelAndProvider == null) {
      ctx.status(400).result("Invalid model or provider");
      return;
    }

    Model modelConfig = modelAndProvider._1();
    Provider provider = modelAndProvider._2();

    jsonObject.put("model", modelConfig.getUpstream());

    String sessionId = ctx.header("X-Session-Id");
    String clientApiKey = ctx.attribute(ClientAuth.CLIENT_KEY_ATTR);

    long start = System.currentTimeMillis();
    Integer statusCode = null;
    String responseText = null;
    Usage usage = null;
    String errorMessage = null;
    try {
      // 构造上游请求也放在审计保护范围内：base_url 非法等异常同样留痕
      HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
          .uri(URI.create(provider.getOpenaiBaseUrl() + "/rerank"))
          .timeout(java.time.Duration.ofSeconds(proxyConfig.getServer().getRequestTimeoutSeconds()))
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(jsonObject.toJSONString()));
      // api_key 为空则不发 Authorization（本地自建/免 key 网关）；配置校验保证厂商直连必有 key
      if (!StringUtils.isBlank(provider.getApiKey())) {
        requestBuilder.header("Authorization", "Bearer " + provider.getApiKey());
      }
      UpstreamHttpClient.forwardClientHeaders(ctx, requestBuilder);
      HttpRequest request = requestBuilder.build();

      log.info("client send");
      HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
      statusCode = response.statusCode();
      log.info("Response status code: {}", statusCode);
      // 上游响应头透传（限流/请求 ID 等），随后显式设置的 Content-Type 覆盖透传值
      UpstreamHttpClient.passThroughHeaders(response, ctx);
      String contentType = response.headers().firstValue("Content-Type").orElse("application/json");
      ctx.contentType(contentType);
      ctx.header("Cache-Control", "no-cache");
      // 空闲读超时：上游停止吐数据后由看门狗 close 流，防止半截挂死永久占用请求线程
      byte[] body;
      try (InputStream in = UpstreamHttpClient.withReadWatchdog(response.body(), java.time.Duration.ofSeconds(proxyConfig.getServer().getReadIdleTimeoutSeconds()))) {
        body = in.readAllBytes();
      }
      ctx.status(statusCode).result(new ByteArrayInputStream(body));
      responseText = new String(body, StandardCharsets.UTF_8);
      usage = extractJsonUsage(responseText);
    }
    catch (Exception e) {
      if (e instanceof InterruptedException) {
        // 恢复中断标志（不吞掉停机信号），与对话 handler 的 rethrow 风格对齐
        Thread.currentThread().interrupt();
      }
      errorMessage = e.toString();
      ctx.status(500).result("Internal Server Error");
    }
    finally {
      // 无论成功失败都落库；conversationStore 内部吞掉 DB 异常，不影响转发
      conversationStore.log(API, sessionId, clientApiKey, model, modelConfig.getProvider(), false, statusCode,
          requestBody, responseText,
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
