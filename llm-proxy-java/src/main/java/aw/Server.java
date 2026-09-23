package aw;

import aw.config.ConfigLoader;
import aw.config.ConfigLoader.ConfigException;
import aw.config.ConfigLoader.ProxyConfig;
import aw.db.ConversationStore;
import aw.httphandler.AnthropicMessagesHttpHandler;
import aw.httphandler.EmbeddingsHttpHandler;
import aw.httphandler.ModelsHttpHandler;
import aw.httphandler.OpenAIChatCompletionsHttpHandler;
import aw.httphandler.OpenAIResponseHttpHandler;
import aw.httphandler.RerankHttpHandler;
import io.javalin.Javalin;
import io.javalin.plugin.bundled.CorsPlugin;
import io.javalin.plugin.bundled.CorsPluginConfig.CorsRule;
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
        proxyConfig.providers().size(), proxyConfig.models().size(), proxyConfig.server().port());
    for (ProxyConfig.Model m : proxyConfig.models()) {
      log.info("路由: {} -> {}/{} [{}]",
          m.name(), m.provider(), m.upstream(), String.join(",", m.capabilities()));
    }

    ConversationStore conversationStore = new ConversationStore("sample.db");

    OpenAIChatCompletionsHttpHandler openAIChatCompletionsHttpHandler = new OpenAIChatCompletionsHttpHandler(conversationStore, proxyConfig);
    OpenAIResponseHttpHandler openAIResponseHttpHandler = new OpenAIResponseHttpHandler(conversationStore, proxyConfig);
    AnthropicMessagesHttpHandler anthropicMessagesHttpHandler = new AnthropicMessagesHttpHandler(conversationStore, proxyConfig);
    EmbeddingsHttpHandler embeddingsHttpHandler = new EmbeddingsHttpHandler(conversationStore, proxyConfig);
    RerankHttpHandler rerankHttpHandler = new RerankHttpHandler(conversationStore, proxyConfig);
    ModelsHttpHandler modelsHttpHandler = new ModelsHttpHandler(proxyConfig);


    var app = Javalin.create(config -> {
      config.concurrency.useVirtualThreads = true;
      config.registerPlugin(new CorsPlugin((c -> c.addRule(CorsRule::anyHost))));
      config.routes.post("/v1/chat/completions", openAIChatCompletionsHttpHandler::handle);
      config.routes.post("/v1/responses", openAIResponseHttpHandler::handle);
      config.routes.post("/v1/messages", anthropicMessagesHttpHandler::handle);
      config.routes.post("/v1/embeddings", embeddingsHttpHandler::handle);
      config.routes.post("/v1/rerank", rerankHttpHandler::handle);
      config.routes.get("/v1/models", modelsHttpHandler::handle);
      config.routes.get("/", ctx -> ctx.result("Hello World"));
    }).start(proxyConfig.server().port());
  }
}
