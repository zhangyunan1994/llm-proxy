package aw.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aw.config.ConfigLoader.ConfigException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
          upstream:
            - provider: local
              model: MiMo-v2.6-Pro
          capability: chat
      """;

  @Test
  void parsesValidConfigWithDefaultTimeouts() {
    ProxyConfig config = ConfigLoader.parse(VALID, "test");
    assertEquals(18080, config.getServer().getPort());
    assertEquals("127.0.0.1", config.getServer().getHost());
    assertEquals(List.of("sk-client"), config.getServer().getClientApiKeys());
    assertEquals(30, config.getServer().getConnectTimeoutSeconds());
    assertEquals(300, config.getServer().getRequestTimeoutSeconds());
    assertEquals(300, config.getServer().getReadIdleTimeoutSeconds());
    assertEquals("local", config.getProviders().get(0).getName());
    assertEquals("m1", config.getModels().get(0).getName());
    // upstream 必填且至少一条，原样透出；lb_policy 可不填，默认 first
    assertEquals(1, config.getModels().get(0).getUpstream().size());
    assertEquals("local", config.getModels().get(0).getUpstream().get(0).getProvider());
    assertEquals("MiMo-v2.6-Pro", config.getModels().get(0).getUpstream().get(0).getModel());
    assertEquals("first", config.getModels().get(0).getLbPolicy());
  }

  @Test
  void parsesModelMetadata() {
    String yaml = VALID.replace("        model: MiMo-v2.6-Pro\n",
        "        model: Up-M\n    max_tokens: 100\n    context_length: 2048\n");
    ProxyConfig.Model model = ConfigLoader.parse(yaml, "test").getModels().get(0);
    assertEquals("Up-M", model.getUpstream().get(0).getModel());
    assertEquals(100, model.getMaxTokens());
    assertEquals(2048, model.getContextLength());
    assertEquals("chat", model.getCapability());
  }

  @Test
  void rejectsModelWithoutUpstream() {
    String yaml = VALID.replace("    upstream:\n      - provider: local\n        model: MiMo-v2.6-Pro\n", "");
    ConfigException e = assertThrows(ConfigException.class, () -> ConfigLoader.parse(yaml, "test"));
    assertTrue(e.getMessage().contains("model [m1] 必须至少配置一个 upstream"));
  }

  @Test
  void defaultsPortAndHostWhenOmitted() {
    String yaml = VALID.replace("  port: 18080\n", "").replace("  host: 127.0.0.1\n", "");
    ProxyConfig config = ConfigLoader.parse(yaml, "test");
    // 缺省端口 18080、缺省地址 127.0.0.1（以告警形式提示，不阻断启动）
    assertEquals(18080, config.getServer().getPort());
    assertEquals("127.0.0.1", config.getServer().getHost());
  }

  @Test
  void rejectsMissingServerSection() {
    String yaml = """
        providers:
          - name: p
            openai_base_url: "http://127.0.0.1:1/v1"
            supported_api_types: ["embeddings"]
        models:
          - name: m1
            upstream:
              - provider: p
                model: u1
            capability: embeddings
        """;
    ConfigException e = assertThrows(ConfigException.class, () -> ConfigLoader.parse(yaml, "test"));
    assertTrue(e.getMessage().contains("server 未配置"));
  }

  @Test
  void rejectsMissingClientApiKeys() {
    String yaml = VALID.replace("  client_api_keys: [\"sk-client\"]\n", "");
    ConfigException e = assertThrows(ConfigException.class, () -> ConfigLoader.parse(yaml, "test"));
    assertTrue(e.getMessage().contains("server.client_api_keys 未配置"));
  }

  @Test
  void rejectsBlankClientApiKey() {
    String yaml = VALID.replace("client_api_keys: [\"sk-client\"]", "client_api_keys: [\"\"]");
    ConfigException e = assertThrows(ConfigException.class, () -> ConfigLoader.parse(yaml, "test"));
    assertTrue(e.getMessage().contains("server.client_api_keys 不能包含空字符串"));
  }

  @Test
  void parsesScalarClientApiKeyAsSingleElementList() {
    // 当前实现（fastjson2 解析）把标量当成单元素列表；老版本的"必须是字符串列表"校验已随重构移除
    String yaml = VALID.replace("client_api_keys: [\"sk-client\"]", "client_api_keys: \"sk-client\"");
    ProxyConfig config = ConfigLoader.parse(yaml, "test");
    assertEquals(List.of("sk-client"), config.getServer().getClientApiKeys());
  }

  @Test
  void rejectsMissingProviders() {
    String yaml = """
        server:
          port: 18080
          client_api_keys: ["sk-client"]
        """;
    ConfigException e = assertThrows(ConfigException.class, () -> ConfigLoader.parse(yaml, "test"));
    assertTrue(e.getMessage().contains("providers 未配置"));
  }

  @Test
  void rejectsMissingModels() {
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
  void requiresApiKeyForBuiltInVendorDomain() {
    String yaml = VALID.replace("http://127.0.0.1:9999/v1", "https://api.deepseek.com/v1")
        .replace("    api_key: \"sk-up\"\n", "");
    ConfigException e = assertThrows(ConfigException.class, () -> ConfigLoader.parse(yaml, "test"));
    assertTrue(e.getMessage().contains("必须配置 api_key"));
  }

  @Test
  void appliesVendorApiKeyRuleToSubdomains() {
    String yaml = VALID.replace("http://127.0.0.1:9999/v1", "https://dashscope.aliyuncs.com/v1")
        .replace("    api_key: \"sk-up\"\n", "");
    assertThrows(ConfigException.class, () -> ConfigLoader.parse(yaml, "test"));
  }

  @Test
  void rejectsPortOutOfRange() {
    String yaml = VALID.replace("port: 18080", "port: 99999");
    ConfigException e = assertThrows(ConfigException.class, () -> ConfigLoader.parse(yaml, "test"));
    assertTrue(e.getMessage().contains("server.port 端口超出范围(1-65535): 99999"));
  }

  @Test
  void rejectsProviderWithoutOpenaiBaseUrl() {
    String yaml = VALID.replace("    openai_base_url: \"http://127.0.0.1:9999/v1\"\n", "");
    ConfigException e = assertThrows(ConfigException.class, () -> ConfigLoader.parse(yaml, "test"));
    assertTrue(e.getMessage().contains("缺少 openai_base_url"));
  }

  @Test
  void rejectsProviderWithoutName() {
    String yaml = """
        server:
          port: 18080
          client_api_keys: ["sk-client"]
        providers:
          - openai_base_url: "http://127.0.0.1:1/v1"
            supported_api_types: ["embeddings"]
        models:
          - name: m1
            upstream:
              - provider: p
                model: u1
            capability: embeddings
        """;
    ConfigException e = assertThrows(ConfigException.class, () -> ConfigLoader.parse(yaml, "test"));
    assertTrue(e.getMessage().contains("第 1 个 provider 缺少 name"));
  }

  @Test
  void rejectsDuplicateProviderName() {
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
            upstream:
              - provider: p
                model: u1
            capability: embeddings
        """;
    ConfigException e = assertThrows(ConfigException.class, () -> ConfigLoader.parse(yaml, "test"));
    assertTrue(e.getMessage().contains("provider name 重复: p"));
  }

  @Test
  void rejectsUpstreamEntryWithoutProvider() {
    String yaml = VALID.replace("      - provider: local\n        model: MiMo-v2.6-Pro",
        "      - model: MiMo-v2.6-Pro");
    ConfigException e = assertThrows(ConfigException.class, () -> ConfigLoader.parse(yaml, "test"));
    assertTrue(e.getMessage().contains("model [m1] upstream[1] 缺少 provider"));
  }

  @Test
  void rejectsUpstreamEntryWithoutModel() {
    String yaml = VALID.replace("        model: MiMo-v2.6-Pro\n", "");
    ConfigException e = assertThrows(ConfigException.class, () -> ConfigLoader.parse(yaml, "test"));
    assertTrue(e.getMessage().contains("model [m1] upstream[1] 缺少 model"));
  }

  @Test
  void rejectsEmptyUpstreamList() {
    String yaml = VALID.replace("    upstream:\n      - provider: local\n        model: MiMo-v2.6-Pro",
        "    upstream: []");
    ConfigException e = assertThrows(ConfigException.class, () -> ConfigLoader.parse(yaml, "test"));
    assertTrue(e.getMessage().contains("model [m1] 必须至少配置一个 upstream"));
  }

  @Test
  void defaultsLbPolicyToFirst() {
    // lb_policy 可不填：校验期补默认值 first（不告警，这是常规写法）
    ProxyConfig config = ConfigLoader.parse(VALID, "test");
    assertEquals("first", config.getModels().get(0).getLbPolicy());
  }

  @Test
  void parsesConfiguredLbPolicy() {
    String yaml = VALID.replace("    capability: chat\n", "    lb_policy: round_robin\n    capability: chat\n");
    assertEquals("round_robin", ConfigLoader.parse(yaml, "test").getModels().get(0).getLbPolicy());
  }

  @Test
  void rejectsInvalidLbPolicy() {
    String yaml = VALID.replace("    capability: chat\n", "    lb_policy: weighted\n    capability: chat\n");
    ConfigException e = assertThrows(ConfigException.class, () -> ConfigLoader.parse(yaml, "test"));
    assertTrue(e.getMessage().contains(
        "model [m1] lb_policy 非法值: weighted（合法: first / random / round_robin）"));
  }

  @Test
  void validatesCapabilityAgainstEveryUpstream() {
    // 第二条 upstream 的 provider 不支持 chat：即便首条支持也要报错（LB 任意一条都可能被选中转发）
    String yaml = VALID
        .replace("supported_api_types: [\"openai.chat.completions\", \"embeddings\", \"rerank\"]",
            "supported_api_types: [\"openai.chat.completions\", \"embeddings\", \"rerank\"]\n"
                + "  - name: embedding-only\n"
                + "    openai_base_url: \"http://127.0.0.1:7777/v1\"\n"
                + "    supported_api_types: [\"embeddings\"]")
        .replace("        model: MiMo-v2.6-Pro\n",
            "        model: MiMo-v2.6-Pro\n      - provider: embedding-only\n        model: Other\n");
    ConfigException e = assertThrows(ConfigException.class, () -> ConfigLoader.parse(yaml, "test"));
    assertTrue(e.getMessage().contains("model [m1] 声明 chat 能力, 但 provider [embedding-only]"));
  }

  @Test
  void rejectsModelReferencingUnknownProvider() {
    String yaml = VALID.replace("      - provider: local\n", "      - provider: nope\n");
    ConfigException e = assertThrows(ConfigException.class, () -> ConfigLoader.parse(yaml, "test"));
    assertTrue(e.getMessage().contains("model [m1] upstream[1] 引用了不存在的 provider: nope"));
  }

  @Test
  void rejectsRerankCapabilityUnsupportedByProvider() {
    // capability 是单值：model 只声明 rerank，provider 不支持 rerank 即报错
    String yaml = VALID.replace("    capability: chat\n", "    capability: rerank\n")
        .replace("supported_api_types: [\"openai.chat.completions\", \"embeddings\", \"rerank\"]",
            "supported_api_types: [\"openai.chat.completions\"]");
    ConfigException e = assertThrows(ConfigException.class, () -> ConfigLoader.parse(yaml, "test"));
    assertTrue(e.getMessage().contains("model [m1] 声明 rerank 能力"));
    assertTrue(e.getMessage().contains("不含 rerank"));
  }

  @Test
  void rejectsEmbeddingsCapabilityUnsupportedByProvider() {
    String yaml = VALID.replace("    capability: chat\n", "    capability: embeddings\n")
        .replace("supported_api_types: [\"openai.chat.completions\", \"embeddings\", \"rerank\"]",
            "supported_api_types: [\"openai.chat.completions\"]");
    ConfigException e = assertThrows(ConfigException.class, () -> ConfigLoader.parse(yaml, "test"));
    assertTrue(e.getMessage().contains("model [m1] 声明 embeddings 能力"));
    assertTrue(e.getMessage().contains("不含 embeddings"));
  }

  @Test
  void rejectsChatCapabilityWithoutChatApiType() {
    String yaml = VALID.replace("supported_api_types: [\"openai.chat.completions\", \"embeddings\", \"rerank\"]",
        "supported_api_types: [\"embeddings\", \"rerank\"]");
    ConfigException e = assertThrows(ConfigException.class, () -> ConfigLoader.parse(yaml, "test"));
    assertTrue(e.getMessage().contains("至少需包含一种 chat 格式"));
  }

  @Test
  void rejectsEmptySupportedApiTypes() {
    String yaml = VALID.replace("supported_api_types: [\"openai.chat.completions\", \"embeddings\", \"rerank\"]",
        "supported_api_types: []");
    ConfigException e = assertThrows(ConfigException.class, () -> ConfigLoader.parse(yaml, "test"));
    assertTrue(e.getMessage().contains("必须配置 supported_api_types"));
  }

  @Test
  void rejectsInvalidSupportedApiType() {
    String yaml = VALID.replace("supported_api_types: [\"openai.chat.completions\", \"embeddings\", \"rerank\"]",
        "supported_api_types: [\"chatgpt\"]");
    ConfigException e = assertThrows(ConfigException.class, () -> ConfigLoader.parse(yaml, "test"));
    assertTrue(e.getMessage().contains("supported_api_types 非法值: chatgpt"));
  }

  @Test
  void rejectsMissingCapability() {
    String yaml = VALID.replace("    capability: chat\n", "");
    ConfigException e = assertThrows(ConfigException.class, () -> ConfigLoader.parse(yaml, "test"));
    assertTrue(e.getMessage().contains("model [m1] 必须配置 capability（不可为空）"));
  }

  @Test
  void rejectsInvalidCapability() {
    String yaml = VALID.replace("    capability: chat\n", "    capability: vision\n");
    ConfigException e = assertThrows(ConfigException.class, () -> ConfigLoader.parse(yaml, "test"));
    assertTrue(e.getMessage().contains("capability 非法值: vision（合法: chat / embeddings / rerank）"));
  }

  @Test
  void rejectsDuplicateModelName() {
    String yaml = VALID.replace("    capability: chat\n",
        "    capability: chat\n"
            + "  - name: m1\n"
            + "    upstream:\n"
            + "      - provider: local\n"
            + "        model: MiMo-v2.6-Pro\n"
            + "    capability: chat\n");
    ConfigException e = assertThrows(ConfigException.class, () -> ConfigLoader.parse(yaml, "test"));
    assertTrue(e.getMessage().contains("model name 重复: m1"));
  }

  @Test
  void loadsConfigFromRelaySystemProperty(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("given.yaml");
    Files.writeString(file, VALID);
    String previous = System.getProperty("llm-relay.config");
    try {
      System.setProperty("llm-relay.config", file.toString());
      // 第一优先级：显式指定的文件一定被读到
      assertEquals(18080, ConfigLoader.load().getServer().getPort());
    }
    finally {
      restoreProperty("llm-relay.config", previous);
    }
  }

  @Test
  void fallsBackToHomeConfigWhenCwdHasNone(@TempDir Path tmp) throws Exception {
    // 前置条件：测试工作目录不能有 ./config.yaml，否则第 2 优先级先命中、根本走不到 $HOME 这一步
    assertFalse(Files.isReadable(Path.of("config.yaml")), "工作目录存在 ./config.yaml，本用例不适用");
    Path homeConfig = tmp.resolve(".config/llm-relay/config.yaml");
    Files.createDirectories(homeConfig.getParent());
    Files.writeString(homeConfig, VALID);
    String previousProp = System.getProperty("llm-relay.config");
    String previousHome = System.getProperty("user.home");
    try {
      System.clearProperty("llm-relay.config");
      System.setProperty("user.home", tmp.toString());
      // 第三优先级：属性与 ./config.yaml 都没有时读 $HOME/.config/llm-relay/config.yaml
      assertEquals(18080, ConfigLoader.load().getServer().getPort());
    }
    finally {
      restoreProperty("llm-relay.config", previousProp);
      restoreProperty("user.home", previousHome);
    }
  }

  private static void restoreProperty(String key, String value) {
    if (value == null) {
      System.clearProperty(key);
    }
    else {
      System.setProperty(key, value);
    }
  }
}
