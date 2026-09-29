package aw;

import aw.auth.ClientAuth;
import aw.config.ConfigLoader;
import aw.config.ConfigLoader.ConfigException;
import aw.config.ProxyConfig;
import aw.db.ConversationStore;
import aw.httphandler.AnthropicMessagesHttpHandler;
import aw.httphandler.EmbeddingsHttpHandler;
import aw.httphandler.ModelsHttpHandler;
import aw.httphandler.OpenAIChatCompletionsHttpHandler;
import aw.httphandler.OpenAIResponseHttpHandler;
import aw.httphandler.RerankHttpHandler;
import aw.httphandler.UpstreamHttpClient;
import io.javalin.Javalin;
import io.javalin.plugin.bundled.CorsPlugin;
import io.javalin.plugin.bundled.CorsPluginConfig.CorsRule;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class Server {

  private static final Logger log = LoggerFactory.getLogger(Server.class);

  public static void main(String[] args) {

    ProxyConfig proxyConfig;
    try {
      proxyConfig = ConfigLoader.load();
    } catch (ConfigException e) {
      log.error("{}", e.getMessage());
      System.exit(1);
      return;
    }
    log.info("配置加载完成: {} 个 provider, {} 个模型, 监听端口 {}",
        proxyConfig.getProviders().size(), proxyConfig.getModels().size(), proxyConfig.getServer().getPort());
    for (ProxyConfig.Model m : proxyConfig.getModels()) {
      String ups = m.getUpstream() == null ? "-"
          : m.getUpstream().stream().map(u -> u.getProvider() + "/" + u.getModel())
              .collect(Collectors.joining(", "));
      log.info("路由: {} -> {} [{}] lb={}", m.getName(), ups, m.getCapability(), m.getLbPolicy());
    }

    // 数据库路径三级优先：-Dllm-relay.db > server.db_path > $HOME/.config/llm-relay/llm-relay.db
    String dbPath = resolveDbPath(proxyConfig);
    Path dbFile = Path.of(dbPath);
    try {
      if (dbFile.getParent() != null) {
        Files.createDirectories(dbFile.getParent()); // 默认的 $HOME/.config/llm-relay 可能尚未存在
      }
    }
    catch (IOException e) {
      log.error("创建数据库目录失败 {}: {}", dbFile.toAbsolutePath().getParent(), e.getMessage());
      System.exit(1);
      return;
    }
    log.info("数据库文件: {}", dbFile.toAbsolutePath());

    ConversationStore conversationStore = new ConversationStore(dbPath);

    // 上游 HttpClient：连接超时来自 server.connect_timeout_seconds，全部 handler 共享
    HttpClient upstreamClient = UpstreamHttpClient.create(
        java.time.Duration.ofSeconds(proxyConfig.getServer().getConnectTimeoutSeconds()));

    OpenAIChatCompletionsHttpHandler openAIChatCompletionsHttpHandler = new OpenAIChatCompletionsHttpHandler(conversationStore, proxyConfig, upstreamClient);
    OpenAIResponseHttpHandler openAIResponseHttpHandler = new OpenAIResponseHttpHandler(conversationStore, proxyConfig, upstreamClient);
    AnthropicMessagesHttpHandler anthropicMessagesHttpHandler = new AnthropicMessagesHttpHandler(conversationStore, proxyConfig, upstreamClient);
    EmbeddingsHttpHandler embeddingsHttpHandler = new EmbeddingsHttpHandler(conversationStore, proxyConfig, upstreamClient);
    RerankHttpHandler rerankHttpHandler = new RerankHttpHandler(conversationStore, proxyConfig, upstreamClient);
    ModelsHttpHandler modelsHttpHandler = new ModelsHttpHandler(proxyConfig);


    var app = Javalin.create(config -> {
      config.concurrency.useVirtualThreads = true;
      // 请求体上限与 handler 的 413 检查共用同一常量（100MB），分块请求无 Content-Length 时由这里兜底
      config.http.maxRequestSize = UpstreamHttpClient.MAX_BODY_BYTES;
      config.registerPlugin(new CorsPlugin((c -> c.addRule(CorsRule::anyHost))));

      // 客户端鉴权：client_api_keys 非空时，/v1/* 全部路由（含 /v1/models）要求合法 Bearer token
      List<String> clientApiKeys = proxyConfig.getServer().getClientApiKeys();

      log.info("客户端鉴权已启用, 共 {} 个 client_api_keys", clientApiKeys.size());
      config.routes.beforeMatched("/v1/*", ctx -> {
        if ("OPTIONS".equalsIgnoreCase(ctx.req().getMethod())) {
          // CORS 预检不带 Authorization，放行交给 CorsPlugin 应答
          return;
        }
        String key = ClientAuth.bearerToken(ctx);
        if (!ClientAuth.matches(key, clientApiKeys)) {
          // 401 不落库（请求体未读取），至少留日志便于发现暴力尝试
          log.warn("鉴权失败: {} {} from {}", ctx.req().getMethod(), ctx.path(), ctx.ip());
          ctx.status(401).result("Unauthorized");
          ctx.skipRemainingHandlers();
          return;
        }
        ctx.attribute(ClientAuth.CLIENT_KEY_ATTR, key);
      });

      config.routes.post("/v1/chat/completions", openAIChatCompletionsHttpHandler::handle);
      config.routes.post("/v1/responses", openAIResponseHttpHandler::handle);
      config.routes.post("/v1/messages", anthropicMessagesHttpHandler::handle);
      config.routes.post("/v1/embeddings", embeddingsHttpHandler::handle);
      config.routes.post("/v1/rerank", rerankHttpHandler::handle);
      config.routes.get("/v1/models", modelsHttpHandler::handle);
      config.routes.get("/", ctx -> ctx.result("Hello World"));
    }).start(proxyConfig.getServer().getHost(), proxyConfig.getServer().getPort());

    String baseUrl = "http://%s:%s".formatted(proxyConfig.getServer().getHost(), proxyConfig.getServer().getPort());

    System.out.printf("""
           _   _   _     _   _   _   _   _ \s
          / \\ / \\ / \\   / \\ / \\ / \\ / \\ / \\\s
         ( l | l | m ) ( r | e | p | a | y )
          \\_/ \\_/ \\_/   \\_/ \\_/ \\_/ \\_/ \\_/\s

        POST %s/v1/chat/completions   OpenAI Chat Completion API
        POST %s/v1/responses	        OpenAI Responses API
        POST %s/v1/messages	          Anthropic Messages API
        POST %s/v1/embeddings         OpenAI Embeddings API — Creates an embedding vector representing the input text.
        POST %s/v1/rerank	            Rank documents against a query — requires a reranker model
        GET  %s/v1/models	            Model metadata
        """, baseUrl, baseUrl, baseUrl, baseUrl, baseUrl, baseUrl);

  }

  /**
   * 数据库文件路径优先级:
   * 1) -Dllm-relay.db=&lt;path&gt;   2) config.yaml 的 server.db_path   3) $HOME/.config/llm-relay/llm-relay.db
   * 指定的值是目录（已存在的目录、以 / 结尾、. 或 ..）时，视为「库放在该目录下」并补默认文件名 llm-relay.db。
   */
  public static String resolveDbPath(ProxyConfig proxyConfig) {
    String candidate = System.getProperty("llm-relay.db");
    if (candidate == null || candidate.isBlank()) {
      candidate = proxyConfig.getServer() == null ? null : proxyConfig.getServer().getDbPath();
    }
    if (candidate == null || candidate.isBlank()) {
      candidate = Path.of(System.getProperty("user.home"), ".config", "llm-relay", "llm-relay.db").toString();
    }
    return asDatabaseFile(candidate.trim());
  }

  /** 目录写法补默认文件名；文件写法原样返回（是否为文件由运行期 SQLite 校验） */
  private static String asDatabaseFile(String path) {
    Path candidate = Path.of(path);
    boolean directory = Files.isDirectory(candidate) || path.endsWith("/") || path.endsWith("\\")
        || path.equals(".") || path.equals("..");
    return directory ? candidate.resolve("llm-relay.db").toString() : path;
  }
}
