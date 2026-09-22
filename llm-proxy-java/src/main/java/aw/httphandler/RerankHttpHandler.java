package aw.httphandler;

import aw.config.ConfigLoader.ProxyConfig;
import aw.config.ConfigLoader.ProxyConfig.Provider;
import aw.util.StringUtils;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import io.javalin.http.Context;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * POST /v1/rerank => Rank documents against a query — requires a reranker model
 */
public class RerankHttpHandler {

  private static final Logger log = LoggerFactory.getLogger(RerankHttpHandler.class);

  HttpClient client = HttpClient.newHttpClient();

  private final ProxyConfig proxyConfig;

  public RerankHttpHandler(ProxyConfig proxyConfig) {
    this.proxyConfig = proxyConfig;
  }

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

    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(provider.openaiBaseUrl() + "/rerank"))
        .header("Authorization", "Bearer " + provider.apiKey())
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
        .build();

    try {
      log.info("client send");
      HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
      log.info("Response status code: {}", response.statusCode());
      String contentType = response.headers().firstValue("Content-Type").orElse("application/json");
      ctx.contentType(contentType);
      ctx.status(response.statusCode()).result(response.body());
    }
    catch (Exception e) {
      ctx.status(500).result("Internal Server Error");
    }
  }


}
