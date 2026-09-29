package aw.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.util.Tuple;

/** 枚举化后的取值解析 + capability 路由查找 + 厂商域名资源加载（A+B 重构的回归） */
class ApiTypeTest {

  @Test
  void apiType按字面值解析且区分大小写() {
    assertEquals(Optional.of(ApiType.OPENAI_CHAT_COMPLETIONS), ApiType.parse("openai.chat.completions"));
    assertEquals(Optional.of(ApiType.RERANK), ApiType.parse("rerank"));
    // 区分大小写与历史 Set.contains 行为一致，非法/空值返回 empty 而不是抛异常
    assertEquals(Optional.empty(), ApiType.parse("RERANK"));
    assertEquals(Optional.empty(), ApiType.parse("chatgpt"));
    assertEquals(Optional.empty(), ApiType.parse(null));
  }

  @Test
  void chatFamily由枚举表达而不是手工子集() {
    assertTrue(ApiType.OPENAI_CHAT_COMPLETIONS.isChat());
    assertTrue(ApiType.OPENAI_RESPONSES.isChat());
    assertTrue(ApiType.ANTHROPIC_MESSAGES.isChat());
    assertFalse(ApiType.EMBEDDINGS.isChat());
    assertFalse(ApiType.RERANK.isChat());
    // chat 合法值文案必须是全集的子集且与配置错误提示一致
    assertEquals("openai.chat.completions / openai.responses / anthropic.messages",
        ApiType.legalChatValues());
    assertTrue(ApiType.legalValues().contains(ApiType.legalChatValues()));
  }

  @Test
  void 合法值提示文案与配置报错保持一致() {
    assertEquals("openai.chat.completions / openai.responses / anthropic.messages / embeddings / rerank",
        ApiType.legalValues());
    assertEquals("chat / embeddings / rerank", Capability.legalValues());
  }

  @Test
  void capability按字面值解析() {
    assertEquals(Optional.of(Capability.CHAT), Capability.parse("chat"));
    assertEquals(Optional.of(Capability.EMBEDDINGS), Capability.parse("embeddings"));
    assertEquals(Optional.empty(), Capability.parse("CHAT"));
    assertEquals(Optional.empty(), Capability.parse("vision"));
    assertEquals(Optional.empty(), Capability.parse(null));
    // toString 输出配置字面值，便于日志/提示
    assertEquals("rerank", Capability.RERANK.toString());
  }

  @Test
  void chat能力按chat类apiType路由() {
    ProxyConfig config = configWith("chat", List.of("openai.chat.completions", "anthropic.messages"));

    Tuple<ProxyConfig.Model, ProxyConfig.Provider> hit =
        config.findModelAndProvider("m1", ApiType.OPENAI_CHAT_COMPLETIONS);
    assertNotNull(hit, "命中应返回 model+provider");
    assertEquals("m1", hit._1().getName());
    assertEquals("p", hit._2().getName());
    assertNotNull(config.findModelAndProvider("m1", ApiType.ANTHROPIC_MESSAGES),
        "provider 声明过的 chat 格式同样命中");

    assertNull(config.findModelAndProvider("m1", ApiType.EMBEDDINGS), "chat 能力不匹配非 chat apiType");
    assertNull(config.findModelAndProvider("nope", ApiType.OPENAI_CHAT_COMPLETIONS), "未知模型返回 null");
  }

  @Test
  void chat能力要求provider声明该具体chat格式() {
    // 启动校验只要求 provider 至少一种 chat 格式；具体请求走哪种格式由这里把关
    ProxyConfig config = configWith("chat", List.of("anthropic.messages"));
    assertNull(config.findModelAndProvider("m1", ApiType.OPENAI_CHAT_COMPLETIONS));
    assertNotNull(config.findModelAndProvider("m1", ApiType.ANTHROPIC_MESSAGES));
  }

  @Test
  void rerank与embeddings能力按对应apiType路由() {
    ProxyConfig rerank = configWith("rerank", List.of("rerank"));
    assertNotNull(rerank.findModelAndProvider("m1", ApiType.RERANK));
    assertNull(rerank.findModelAndProvider("m1", ApiType.OPENAI_CHAT_COMPLETIONS));

    ProxyConfig embeddings = configWith("embeddings", List.of("embeddings"));
    assertNotNull(embeddings.findModelAndProvider("m1", ApiType.EMBEDDINGS));
    assertNull(embeddings.findModelAndProvider("m1", ApiType.RERANK));
  }

  @Test
  void 非法或缺失的capability匹配不到任何api() {
    // 非法值与缺失值都解析不出 Capability，两个分支都不成立 => null（不抛 NPE）
    ProxyConfig invalid = configWith("vision", List.of("openai.chat.completions"));
    assertNull(invalid.findModelAndProvider("m1", ApiType.OPENAI_CHAT_COMPLETIONS));
    ProxyConfig missing = configWith(null, List.of("openai.chat.completions"));
    assertNull(missing.findModelAndProvider("m1", ApiType.OPENAI_CHAT_COMPLETIONS));
  }

  /** 单 provider 单 model 的最小配置：capability 单值 + provider 的 supported_api_types */
  private static ProxyConfig configWith(String capability, List<String> providerApiTypes) {
    ProxyConfig.Provider provider =
        new ProxyConfig.Provider("p", "http://127.0.0.1:1/v1", null, "k", providerApiTypes);
    ProxyConfig.Model model = new ProxyConfig.Model("m1", "p", "up", null, null, capability);
    return new ProxyConfig(null, List.of(provider), List.of(model));
  }

  @Test
  void 厂商域名表从资源文件加载() {
    // 资源缺失时 KeyRequiredDomains 静态初始化即抛异常，这里断言确实加载到了数据
    assertTrue(KeyRequiredDomains.size() > 300, "域名清单应从 key-required-domains.txt 加载: "
        + KeyRequiredDomains.size());
    assertTrue(KeyRequiredDomains.contains("api.deepseek.com"), "根域名应命中");
    assertTrue(KeyRequiredDomains.contains("dashscope.aliyuncs.com"), "子域名应命中");
    assertFalse(KeyRequiredDomains.contains("example.com"), "非内置厂商不命中");
    // 后缀匹配不能被 evildeepseek.com 这类前缀伪装绕过
    assertFalse(KeyRequiredDomains.contains("evildeepseek.com"), "前缀伪装不应命中");
    assertFalse(KeyRequiredDomains.contains("deepseek.com.evil.com"), "后缀伪装不应命中");
  }
}
