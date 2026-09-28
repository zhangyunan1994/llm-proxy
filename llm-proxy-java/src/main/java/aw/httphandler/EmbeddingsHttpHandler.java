package aw.httphandler;

import aw.auth.ClientAuth;
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
 * POST /v1/embeddings => Embeddings — requires a pooling-enabled model (see serving)
 */
public class EmbeddingsHttpHandler {

  private static final Logger log = LoggerFactory.getLogger(EmbeddingsHttpHandler.class);

  /** 落库用的来源接口标识 */
  private static final String API = "embeddings";

  HttpClient client = HttpClient.newHttpClient();

  private final ConversationStore conversationStore;
  private final ProxyConfig proxyConfig;

  public EmbeddingsHttpHandler(ConversationStore conversationStore, ProxyConfig proxyConfig) {
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
    String clientApiKey = ctx.attribute(ClientAuth.CLIENT_KEY_ATTR);

    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(provider.openaiBaseUrl() + "/embeddings"))
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
      conversationStore.log(API, sessionId, clientApiKey, model, modelConfig.provider(), false, statusCode,
          requestBody, responseText,
          usage == null ? null : usage.promptTokens(),
          usage == null ? null : usage.completionTokens(),
          usage == null ? null : usage.totalTokens(),
          usage == null ? null : usage.cacheTokens(),
          System.currentTimeMillis() - start, errorMessage, null);
    }
  }

  /** 非流式响应：embeddings 的 usage 只有 prompt_tokens / total_tokens，无缓存概念 */
  private static Usage extractJsonUsage(String responseBody) {
    try {
      JSONObject usage = JSON.parseObject(responseBody).getJSONObject("usage");
      if (usage == null) {
        return null;
      }
      return new Usage(usage.getInteger("prompt_tokens"), null,
          usage.getInteger("total_tokens"), null);
    } catch (Exception e) {
      log.debug("解析响应 usage 失败", e);
      return null;
    }
  }
}
