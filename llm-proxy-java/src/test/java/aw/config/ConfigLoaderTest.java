package aw.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aw.config.ConfigLoader.ConfigException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 配置校验核心路径：默认值、鉴权 key 校验、厂商域名强制 key、端口/超时校验、capability 路由校验。
 * 配置结构以当前 ProxyConfig（port/host + getter 风格）为准。
 */
class ConfigLoaderTest {

  private static final String VALID = """
      server:
        port: 18080
        host: 127.0.0.1
        client_api_keys: ["sk-client"]
      providers:
        - name: local
          openai_base_url: "http://127.0.0.1:9999/v1"
          api_key: "sk-up"
          supported_api_types: ["openai.chat.completions", "embeddings", "rerank"]
      models:
        - name: m1
          provider: local
          upstream: MiMo-v2.6-Pro
          capability: chat
      """;

  @Test
  void 合法配置解析并带默认超时() {
    ProxyConfig config = ConfigLoader.parse(VALID, "test");
    assertEquals(18080, config.getServer().getPort());
    assertEquals("127.0.0.1", config.getServer().getHost());
    assertEquals(List.of("sk-client"), config.getServer().getClientApiKeys());
    assertEquals(30, config.getServer().getConnectTimeoutSeconds());
    assertEquals(300, config.getServer().getRequestTimeoutSeconds());
    assertEquals(300, config.getServer().getReadIdleTimeoutSeconds());
    assertEquals("local", config.getProviders().get(0).getName());
    assertEquals("m1", config.getModels().get(0).getName());
    // upstream 必填，原样透出
    assertEquals("MiMo-v2.6-Pro", config.getModels().get(0).getUpstream());
  }

  @Test
  void 模型元数据解析() {
    String yaml = VALID.replace("    upstream: MiMo-v2.6-Pro\n",
        "    upstream: Up-M\n    max_tokens: 100\n    context_length: 2048\n");
    ProxyConfig.Model model = ConfigLoader.parse(yaml, "test").getModels().get(0);
    assertEquals("Up-M", model.getUpstream());
    assertEquals(100, model.getMaxTokens());
    assertEquals(2048, model.getContextLength());
    assertEquals("chat", model.getCapability());
  }

  @Test
  void upstream缺失报错() {
    String yaml = VALID.replace("    upstream: MiMo-v2.6-Pro\n", "");
    ConfigException e = assertThrows(ConfigException.class, () -> ConfigLoader.parse(yaml, "test"));
    assertTrue(e.getMessage().contains("model [m1] 缺少 upstream"));
  }

  @Test
  void 缺省端口与主机走默认值() {
    String yaml = VALID.replace("  port: 18080\n", "").replace("  host: 127.0.0.1\n", "");
    ProxyConfig config = ConfigLoader.parse(yaml, "test");
    // 缺省端口 18080、缺省地址 127.0.0.1（以告警形式提示，不阻断启动）
    assertEquals(18080, config.getServer().getPort());
    assertEquals("127.0.0.1", config.getServer().getHost());
  }

  @Test
  void server未配置报错() {
    String yaml = """
        providers:
          - name: p
            openai_base_url: "http://127.0.0.1:1/v1"
            supported_api_types: ["embeddings"]
        models:
          - name: m1
            provider: p
            upstream: u1
            capability: embeddings
        """;
    ConfigException e = assertThrows(ConfigException.class, () -> ConfigLoader.parse(yaml, "test"));
    assertTrue(e.getMessage().contains("server 未配置"));
  }

  @Test
  void clientApiKeys缺失报错() {
    String yaml = VALID.replace("  client_api_keys: [\"sk-client\"]\n", "");
    ConfigException e = assertThrows(ConfigException.class, () -> ConfigLoader.parse(yaml, "test"));
    assertTrue(e.getMessage().contains("server.client_api_keys 未配置"));
  }

  @Test
  void 空clientKey条目启动报错() {
    String yaml = VALID.replace("client_api_keys: [\"sk-client\"]", "client_api_keys: [\"\"]");
    ConfigException e = assertThrows(ConfigException.class, () -> ConfigLoader.parse(yaml, "test"));
    assertTrue(e.getMessage().contains("server.client_api_keys 不能包含空字符串"));
  }

  @Test
  void 标量clientApiKeys按单个key解析() {
    // 当前实现（fastjson2 解析）把标量当成单元素列表；老版本的"必须是字符串列表"校验已随重构移除
    String yaml = VALID.replace("client_api_keys: [\"sk-client\"]", "client_api_keys: \"sk-client\"");
    ProxyConfig config = ConfigLoader.parse(yaml, "test");
    assertEquals(List.of("sk-client"), config.getServer().getClientApiKeys());
  }

  @Test
  void providers未配置报错() {
    String yaml = """
        server:
          port: 18080
          client_api_keys: ["sk-client"]
        """;
    ConfigException e = assertThrows(ConfigException.class, () -> ConfigLoader.parse(yaml, "test"));
    assertTrue(e.getMessage().contains("providers 未配置"));
  }

  @Test
  void models未配置报错() {
    String yaml = """
        server:
          port: 18080
          client_api_keys: ["sk-client"]
        providers:
          - name: p
            openai_base_url: "http://127.0.0.1:1/v1"
            supported_api_types: ["embeddings"]
        """;
    ConfigException e = assertThrows(ConfigException.class, () -> ConfigLoader.parse(yaml, "test"));
    assertTrue(e.getMessage().contains("models 未配置"));
  }

  @Test
  void 厂商域名缺api_key启动报错() {
    String yaml = VALID.replace("http://127.0.0.1:9999/v1", "https://api.deepseek.com/v1")
        .replace("    api_key: \"sk-up\"\n", "");
    ConfigException e = assertThrows(ConfigException.class, () -> ConfigLoader.parse(yaml, "test"));
    assertTrue(e.getMessage().contains("必须配置 api_key"));
  }

  @Test
  void 子域名也命中厂商校验() {
    String yaml = VALID.replace("http://127.0.0.1:9999/v1", "https://dashscope.aliyuncs.com/v1")
        .replace("    api_key: \"sk-up\"\n", "");
    assertThrows(ConfigException.class, () -> ConfigLoader.parse(yaml, "test"));
  }

  @Test
  void 端口超范围报错() {
    String yaml = VALID.replace("port: 18080", "port: 99999");
    ConfigException e = assertThrows(ConfigException.class, () -> ConfigLoader.parse(yaml, "test"));
    assertTrue(e.getMessage().contains("server.port 端口超出范围(1-65535): 99999"));
  }

  @Test
  void provider缺少openaiBaseUrl报错() {
    String yaml = VALID.replace("    openai_base_url: \"http://127.0.0.1:9999/v1\"\n", "");
    ConfigException e = assertThrows(ConfigException.class, () -> ConfigLoader.parse(yaml, "test"));
    assertTrue(e.getMessage().contains("缺少 openai_base_url"));
  }

  @Test
  void provider缺少name报错() {
    String yaml = """
        server:
          port: 18080
          client_api_keys: ["sk-client"]
        providers:
          - openai_base_url: "http://127.0.0.1:1/v1"
            supported_api_types: ["embeddings"]
        models:
          - name: m1
            provider: p
            upstream: u1
            capability: embeddings
        """;
    ConfigException e = assertThrows(ConfigException.class, () -> ConfigLoader.parse(yaml, "test"));
    assertTrue(e.getMessage().contains("第 1 个 provider 缺少 name"));
  }

  @Test
  void 重复provider名报错() {
    String yaml = """
        server:
          port: 18080
          client_api_keys: ["sk-client"]
        providers:
          - name: p
            openai_base_url: "http://127.0.0.1:1/v1"
            supported_api_types: ["embeddings"]
          - name: p
            openai_base_url: "http://127.0.0.1:2/v1"
            supported_api_types: ["embeddings"]
        models:
          - name: m1
            provider: p
            upstream: u1
            capability: embeddings
        """;
    ConfigException e = assertThrows(ConfigException.class, () -> ConfigLoader.parse(yaml, "test"));
    assertTrue(e.getMessage().contains("provider name 重复: p"));
  }

  @Test
  void model缺少provider报错() {
    String yaml = VALID.replace("    provider: local\n", "");
    ConfigException e = assertThrows(ConfigException.class, () -> ConfigLoader.parse(yaml, "test"));
    assertTrue(e.getMessage().contains("model [m1] 缺少 provider"));
  }

  @Test
  void model引用不存在的provider报错() {
    String yaml = VALID.replace("    provider: local\n", "    provider: nope\n");
    ConfigException e = assertThrows(ConfigException.class, () -> ConfigLoader.parse(yaml, "test"));
    assertTrue(e.getMessage().contains("引用了不存在的 provider: nope"));
  }

  @Test
  void rerank能力路由校验() {
    // capability 是单值：model 只声明 rerank，provider 不支持 rerank 即报错
    String yaml = VALID.replace("    capability: chat\n", "    capability: rerank\n")
        .replace("supported_api_types: [\"openai.chat.completions\", \"embeddings\", \"rerank\"]",
            "supported_api_types: [\"openai.chat.completions\"]");
    ConfigException e = assertThrows(ConfigException.class, () -> ConfigLoader.parse(yaml, "test"));
    assertTrue(e.getMessage().contains("model [m1] 声明 rerank 能力"));
    assertTrue(e.getMessage().contains("不含 rerank"));
  }

  @Test
  void embeddings能力路由校验() {
    String yaml = VALID.replace("    capability: chat\n", "    capability: embeddings\n")
        .replace("supported_api_types: [\"openai.chat.completions\", \"embeddings\", \"rerank\"]",
            "supported_api_types: [\"openai.chat.completions\"]");
    ConfigException e = assertThrows(ConfigException.class, () -> ConfigLoader.parse(yaml, "test"));
    assertTrue(e.getMessage().contains("model [m1] 声明 embeddings 能力"));
    assertTrue(e.getMessage().contains("不含 embeddings"));
  }

  @Test
  void chat能力需要至少一种chat格式() {
    String yaml = VALID.replace("supported_api_types: [\"openai.chat.completions\", \"embeddings\", \"rerank\"]",
        "supported_api_types: [\"embeddings\", \"rerank\"]");
    ConfigException e = assertThrows(ConfigException.class, () -> ConfigLoader.parse(yaml, "test"));
    assertTrue(e.getMessage().contains("至少需包含一种 chat 格式"));
  }

  @Test
  void supportedApiTypes为空报错() {
    String yaml = VALID.replace("supported_api_types: [\"openai.chat.completions\", \"embeddings\", \"rerank\"]",
        "supported_api_types: []");
    ConfigException e = assertThrows(ConfigException.class, () -> ConfigLoader.parse(yaml, "test"));
    assertTrue(e.getMessage().contains("必须配置 supported_api_types"));
  }

  @Test
  void supportedApiTypes非法值报错() {
    String yaml = VALID.replace("supported_api_types: [\"openai.chat.completions\", \"embeddings\", \"rerank\"]",
        "supported_api_types: [\"chatgpt\"]");
    ConfigException e = assertThrows(ConfigException.class, () -> ConfigLoader.parse(yaml, "test"));
    assertTrue(e.getMessage().contains("supported_api_types 非法值: chatgpt"));
  }

  @Test
  void capability缺失报错() {
    String yaml = VALID.replace("    capability: chat\n", "");
    ConfigException e = assertThrows(ConfigException.class, () -> ConfigLoader.parse(yaml, "test"));
    assertTrue(e.getMessage().contains("model [m1] 必须配置 capability（不可为空）"));
  }

  @Test
  void capability非法值报错() {
    String yaml = VALID.replace("    capability: chat\n", "    capability: vision\n");
    ConfigException e = assertThrows(ConfigException.class, () -> ConfigLoader.parse(yaml, "test"));
    assertTrue(e.getMessage().contains("capability 非法值: vision（合法: chat / embeddings / rerank）"));
  }

  @Test
  void 重复模型名报错() {
    String yaml = VALID.replace("    capability: chat\n",
        "    capability: chat\n"
            + "  - name: m1\n"
            + "    provider: local\n"
            + "    upstream: MiMo-v2.6-Pro\n"
            + "    capability: chat\n");
    ConfigException e = assertThrows(ConfigException.class, () -> ConfigLoader.parse(yaml, "test"));
    assertTrue(e.getMessage().contains("model name 重复: m1"));
  }
}
