package aw.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 枚举化的取值解析（ApiType/Capability/LbPolicy）+ capability 路由查找 + 厂商域名资源加载 */
class ApiTypeTest {

  @Test
  void parsesApiTypeLiteralCaseSensitively() {
    assertEquals(Optional.of(ApiType.OPENAI_CHAT_COMPLETIONS), ApiType.parse("openai.chat.completions"));
    assertEquals(Optional.of(ApiType.RERANK), ApiType.parse("rerank"));
    // 区分大小写与历史 Set.contains 行为一致，非法/空值返回 empty 而不是抛异常
    assertEquals(Optional.empty(), ApiType.parse("RERANK"));
    assertEquals(Optional.empty(), ApiType.parse("chatgpt"));
    assertEquals(Optional.empty(), ApiType.parse(null));
  }

  @Test
  void expressesChatFamilyViaEnumFlag() {
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
  void keepsLegalValueHintsInSyncWithConfigErrors() {
    assertEquals("openai.chat.completions / openai.responses / anthropic.messages / embeddings / rerank",
        ApiType.legalValues());
    assertEquals("chat / embeddings / rerank", Capability.legalValues());
  }

  @Test
  void parsesCapabilityLiteral() {
    assertEquals(Optional.of(Capability.CHAT), Capability.parse("chat"));
    assertEquals(Optional.of(Capability.EMBEDDINGS), Capability.parse("embeddings"));
    assertEquals(Optional.empty(), Capability.parse("CHAT"));
    assertEquals(Optional.empty(), Capability.parse("vision"));
    assertEquals(Optional.empty(), Capability.parse(null));
    // toString 输出配置字面值，便于日志/提示
    assertEquals("rerank", Capability.RERANK.toString());
  }

  @Test
  void parsesLbPolicyLiteral() {
    assertEquals(Optional.of(LbPolicy.FIRST), LbPolicy.parse("first"));
    assertEquals(Optional.of(LbPolicy.RANDOM), LbPolicy.parse("random"));
    assertEquals(Optional.of(LbPolicy.ROUND_ROBIN), LbPolicy.parse("round_robin"));
    // 区分大小写，非法/空值返回 empty 而不是抛异常
    assertEquals(Optional.empty(), LbPolicy.parse("FIRST"));
    assertEquals(Optional.empty(), LbPolicy.parse("weighted"));
    assertEquals(Optional.empty(), LbPolicy.parse(null));
    assertEquals("first / random / round_robin", LbPolicy.legalValues());
    assertEquals("round_robin", LbPolicy.ROUND_ROBIN.toString());
  }

  @Test
  void routesChatCapabilityByChatApiType() {
    ProxyConfig config = configWith("chat", List.of("openai.chat.completions", "anthropic.messages"));

    ProxyConfig.Route hit = config.findModelAndProvider("m1", ApiType.OPENAI_CHAT_COMPLETIONS);
    assertNotNull(hit, "命中应返回 model+provider+上游模型名");
    assertEquals("m1", hit.model().getName());
    assertEquals("p", hit.provider().getName());
    assertEquals("up", hit.upstreamModel());
    assertNotNull(config.findModelAndProvider("m1", ApiType.ANTHROPIC_MESSAGES),
        "provider 声明过的 chat 格式同样命中");

    assertNull(config.findModelAndProvider("m1", ApiType.EMBEDDINGS), "chat 能力不匹配非 chat apiType");
    assertNull(config.findModelAndProvider("nope", ApiType.OPENAI_CHAT_COMPLETIONS), "未知模型返回 null");
  }

  @Test
  void requiresProviderToDeclareSpecificChatApiType() {
    // 启动校验只要求 provider 至少一种 chat 格式；具体请求走哪种格式由这里把关
    ProxyConfig config = configWith("chat", List.of("anthropic.messages"));
    assertNull(config.findModelAndProvider("m1", ApiType.OPENAI_CHAT_COMPLETIONS));
    assertNotNull(config.findModelAndProvider("m1", ApiType.ANTHROPIC_MESSAGES));
  }

  @Test
  void routesRerankAndEmbeddingsByMatchingApiType() {
    ProxyConfig rerank = configWith("rerank", List.of("rerank"));
    assertNotNull(rerank.findModelAndProvider("m1", ApiType.RERANK));
    assertNull(rerank.findModelAndProvider("m1", ApiType.OPENAI_CHAT_COMPLETIONS));

    ProxyConfig embeddings = configWith("embeddings", List.of("embeddings"));
    assertNotNull(embeddings.findModelAndProvider("m1", ApiType.EMBEDDINGS));
    assertNull(embeddings.findModelAndProvider("m1", ApiType.RERANK));
  }

  @Test
  void matchesNoApiTypeForInvalidOrMissingCapability() {
    // 非法值与缺失值都解析不出 Capability，两个分支都不成立 => null（不抛 NPE）
    ProxyConfig invalid = configWith("vision", List.of("openai.chat.completions"));
    assertNull(invalid.findModelAndProvider("m1", ApiType.OPENAI_CHAT_COMPLETIONS));
    ProxyConfig missing = configWith(null, List.of("openai.chat.completions"));
    assertNull(missing.findModelAndProvider("m1", ApiType.OPENAI_CHAT_COMPLETIONS));
  }

  @Test
  void routesResponsesEndpointByOpenaiResponsesApiType() {
    // provider 只声明 openai.responses 时，/v1/responses 必须能命中、
    // 而按 chat.completions 校验会误判成 400（handler 传错 ApiType 的回归）
    ProxyConfig config = configWith("chat", List.of("openai.responses"));
    assertNotNull(config.findModelAndProvider("m1", ApiType.OPENAI_RESPONSES),
        "只声明 openai.responses 的 provider 应能服务 /v1/responses");
    assertNull(config.findModelAndProvider("m1", ApiType.OPENAI_CHAT_COMPLETIONS));
  }

  /** 单 provider 单 model 的最小配置：capability 单值 + provider 的 supported_api_types */
  private static ProxyConfig configWith(String capability, List<String> providerApiTypes) {
    ProxyConfig.Provider provider =
        new ProxyConfig.Provider("p", "http://127.0.0.1:1/v1", null, "k", providerApiTypes);
    ProxyConfig.Model model = new ProxyConfig.Model("m1", "p", "up", null, null, capability);
    return new ProxyConfig(null, List.of(provider), List.of(model));
  }

  @Test
  void picksFirstUpstreamWhenLbPolicyOmitted() {
    ProxyConfig config = twoUpstreamConfig(null);
    // lb_policy 可不填 => 恒取首条，多次调用结果稳定
    for (int i = 0; i < 20; i++) {
      ProxyConfig.Route route = config.findModelAndProvider("m1", ApiType.OPENAI_CHAT_COMPLETIONS);
      assertNotNull(route, "首条即候选，应命中");
      assertEquals("p1", route.provider().getName());
      assertEquals("up1", route.upstreamModel());
    }
  }

  @Test
  void roundRobinAlternatesAcrossUpstreams() {
    ProxyConfig config = twoUpstreamConfig("round_robin");
    // 计数器按模型名从 0 起，前两轮应依次命中 p1/p2，如此往复
    for (int i = 0; i < 6; i++) {
      ProxyConfig.Route route = config.findModelAndProvider("m1", ApiType.OPENAI_CHAT_COMPLETIONS);
      assertNotNull(route);
      String expectProvider = i % 2 == 0 ? "p1" : "p2";
      String expectModel = i % 2 == 0 ? "up1" : "up2";
      assertEquals(expectProvider, route.provider().getName());
      assertEquals(expectModel, route.upstreamModel());
    }
  }

  @Test
  void randomStaysWithinCandidates() {
    ProxyConfig config = twoUpstreamConfig("random");
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < 50; i++) {
      ProxyConfig.Route route = config.findModelAndProvider("m1", ApiType.OPENAI_CHAT_COMPLETIONS);
      assertNotNull(route);
      seen.add(route.provider().getName());
    }
    // 只在候选里选：50 次内两条都应至少命中一次（全不命中的概率约 2^-49）
    assertEquals(Set.of("p1", "p2"), seen);
  }

  @Test
  void filtersCandidatesByRequestedApiType() {
    // 两条 upstream 各自只支持一种 chat 格式：候选先按 apiType 过滤，再谈 lb_policy
    ProxyConfig.Provider p1 =
        new ProxyConfig.Provider("p1", "http://127.0.0.1:1/v1", null, "k", List.of("anthropic.messages"));
    ProxyConfig.Provider p2 =
        new ProxyConfig.Provider("p2", "http://127.0.0.1:2/v1", null, "k", List.of("openai.responses"));
    ProxyConfig.Model model = new ProxyConfig.Model("m1",
        List.of(new ProxyConfig.Upstream("p1", "up1"), new ProxyConfig.Upstream("p2", "up2")),
        "round_robin", null, null, "chat");
    ProxyConfig config = new ProxyConfig(null, List.of(p1, p2), List.of(model));

    ProxyConfig.Route messages = config.findModelAndProvider("m1", ApiType.ANTHROPIC_MESSAGES);
    assertEquals("p1", messages.provider().getName());
    assertEquals("up1", messages.upstreamModel());
    ProxyConfig.Route responses = config.findModelAndProvider("m1", ApiType.OPENAI_RESPONSES);
    assertEquals("p2", responses.provider().getName());
    assertEquals("up2", responses.upstreamModel());
    // 候选为空返回 null，而不是选中一条打到上游 404
    assertNull(config.findModelAndProvider("m1", ApiType.OPENAI_CHAT_COMPLETIONS),
        "没有 upstream 支持该 chat 格式 => 无候选");
  }

  /** 双 upstream 的最小配置：p1/p2 都支持 chat.completions，便于观察 lb_policy 的选路 */
  private static ProxyConfig twoUpstreamConfig(String lbPolicy) {
    ProxyConfig.Provider p1 =
        new ProxyConfig.Provider("p1", "http://127.0.0.1:1/v1", null, "k", List.of("openai.chat.completions"));
    ProxyConfig.Provider p2 =
        new ProxyConfig.Provider("p2", "http://127.0.0.1:2/v1", null, "k", List.of("openai.chat.completions"));
    ProxyConfig.Model model = new ProxyConfig.Model("m1",
        List.of(new ProxyConfig.Upstream("p1", "up1"), new ProxyConfig.Upstream("p2", "up2")),
        lbPolicy, null, null, "chat");
    return new ProxyConfig(null, List.of(p1, p2), List.of(model));
  }

  @Test
  void loadsVendorDomainListFromResource() {
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
