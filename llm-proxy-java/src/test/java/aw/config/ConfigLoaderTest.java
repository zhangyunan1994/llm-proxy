package aw.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aw.config.ConfigLoader.ConfigException;
import aw.config.ConfigLoader.ProxyConfig;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 配置校验核心路径：默认值、鉴权 key 校验、厂商域名强制 key、超时校验、capabilities 路由校验 */
class ConfigLoaderTest {

  private static final String VALID = """
      server:
        addr: ":18080"
        client_api_keys: ["sk-client"]
      providers:
        - name: local
          openai_base_url: "http://127.0.0.1:9999/v1"
          api_key: "sk-up"
          supported_api_types: ["openai.chat.completions", "embeddings", "rerank"]
      models:
        - name: m1
          provider: local
          capabilities: ["chat", "embeddings", "rerank"]
      """;

  @Test
  void 合法配置解析并带默认超时() {
    ProxyConfig config = ConfigLoader.parse(VALID, "test");
    assertEquals(18080, config.server().port());
    assertEquals(List.of("sk-client"), config.server().clientApiKeys());
    assertEquals(30, config.server().connectTimeoutSeconds());
    assertEquals(300, config.server().requestTimeoutSeconds());
    assertEquals(300, config.server().readIdleTimeoutSeconds());
    assertEquals("m1", config.models().get(0).name());
    // upstream 缺省等于 name
    assertEquals("m1", config.models().get(0).upstream());
  }

  @Test
  void 空clientKey条目启动报错() {
    String yaml = """
        server:
          client_api_keys: [""]
        providers:
          - name: p
            openai_base_url: "http://127.0.0.1:1/v1"
        models:
          - name: m1
            provider: p
        """;
    ConfigException e = assertThrows(ConfigException.class, () -> ConfigLoader.parse(yaml, "test"));
    assertTrue(e.getMessage().contains("client_api_keys[0] 为空"));
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
  void 非法超时报错() {
    String yaml = VALID.replace("addr: \":18080\"", "addr: \":18080\"\n        request_timeout_seconds: -5");
    ConfigException e = assertThrows(ConfigException.class, () -> ConfigLoader.parse(yaml, "test"));
    assertTrue(e.getMessage().contains("request_timeout_seconds"));
  }

  @Test
  void rerank能力路由校验() {
    String yaml = VALID.replace("supported_api_types: [\"openai.chat.completions\", \"embeddings\", \"rerank\"]",
        "supported_api_types: [\"openai.chat.completions\"]");
    ConfigException e = assertThrows(ConfigException.class, () -> ConfigLoader.parse(yaml, "test"));
    assertTrue(e.getMessage().contains("不含 rerank"));
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
  void supportedApiTypes为空时不限制路由() {
    String yaml = VALID.replace("supported_api_types: [\"openai.chat.completions\", \"embeddings\", \"rerank\"]",
        "supported_api_types: []");
    assertNotNull(ConfigLoader.parse(yaml, "test"));
  }

  @Test
  void 重复模型名报错() {
    String yaml = """
        server:
          client_api_keys: ["k"]
        providers:
          - name: local
            openai_base_url: "http://127.0.0.1:9999/v1"
        models:
          - name: m1
            provider: local
          - name: m1
            provider: local
        """;
    ConfigException e = assertThrows(ConfigException.class, () -> ConfigLoader.parse(yaml, "test"));
    assertTrue(e.getMessage().contains("model name 重复"));
  }
}
