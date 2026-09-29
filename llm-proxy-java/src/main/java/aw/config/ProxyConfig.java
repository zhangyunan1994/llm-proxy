package aw.config;

import aw.util.StringUtils;
import java.net.URI;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;


@Setter
@Getter
@NoArgsConstructor
@ToString
public class ProxyConfig {

  private Server server;
  private List<Provider> providers;
  private List<Model> models;

  /** lb_policy=round_robin 的轮询计数器（按模型名各自计数，进程内内存态，重启归零） */
  private final Map<String, AtomicInteger> roundRobinCounters = new ConcurrentHashMap<>();

  public ProxyConfig(Server server, List<Provider> providers, List<Model> models) {
    this.server = server;
    this.providers = providers;
    this.models = models;
  }

  public void validateConfig(List<String> errors, List<String> warnings) {
    if (server == null) {
      errors.add("server 未配置");
    }
    else {
      if (server.getPort() == 0) {
        warnings.add("server.port 未配置,使用默认端口 18080");
        server.setPort(18080);
      }
      else if (server.getPort() < 1 || server.getPort() > 65535) {
        errors.add("server.port 端口超出范围(1-65535): " + server.getPort());
      }
      if (server.getHost() == null || server.getHost().isEmpty()) {
        warnings.add("server.host 未配置,使用默认地址 127.0.0.1");
        server.setHost("127.0.0.1");
      }
      List<String> clientApiKeys = server.getClientApiKeys();
      if (clientApiKeys == null || clientApiKeys.isEmpty()) {
        errors.add("server.client_api_keys 未配置");
      }
      else if (clientApiKeys.stream().anyMatch(apiKey -> apiKey == null || apiKey.trim().isEmpty())) {
        errors.add("server.client_api_keys 不能包含空字符串");
      }

      server.setConnectTimeoutSeconds(
          server.getConnectTimeoutSeconds() != null ? server.getConnectTimeoutSeconds() : 30);
      server.setRequestTimeoutSeconds(
          server.getRequestTimeoutSeconds() != null ? server.getRequestTimeoutSeconds() : 300);
      server.setReadIdleTimeoutSeconds(
          server.getReadIdleTimeoutSeconds() != null ? server.getReadIdleTimeoutSeconds() : 300);
    }

    // ---- providers(厂商唯一校验)----
    if (providers == null || providers.isEmpty()) {
      errors.add("providers 未配置");
      // providers 为空时，后续的验证没必要进行，因此直接返回
      return;
    }

    Set<String> providerNames = new HashSet<>();
    Map<String, List<String>> providerApiTypes = new HashMap<>();

    for (int providerIndex = 0; providerIndex < providers.size(); providerIndex++) {

      Provider provider = providers.get(providerIndex);

      String name = provider.getName();
      if (StringUtils.isBlank(name)) {
        errors.add("第 " + (providerIndex + 1) + " 个 provider 缺少 name");
        continue;
      }

      if (!providerNames.add(name)) {
        errors.add("provider name 重复: " + name);
      }

      if (StringUtils.isBlank(provider.getOpenaiBaseUrl())) {
        errors.add("provider [" + name + "] 缺少 openai_base_url (anthropic_base_url 可选, 缺省回退 openai_base_url)");
      }
      // 直连内置厂商域名时必须配 api_key；本地自建/免 key 网关可省略（运行时不发 Authorization 头）
      if (StringUtils.isBlank(provider.getApiKey())
          && (requiresApiKey(provider.getOpenaiBaseUrl()) || requiresApiKey(provider.getAnthropicBaseUrl()))) {
        errors.add("provider [" + name + "] 直连内置厂商域名, 必须配置 api_key");
      }
      List<String> apiTypes = provider.getSupportedApiTypes();
      if (apiTypes == null) {
        apiTypes = List.of();
      }
      if (apiTypes.isEmpty()) {
        errors.add("provider [" + name + "] 必须配置 supported_api_types（不可为空）");
      }
      else {
        for (String t : apiTypes) {
          if (ApiType.parse(t).isEmpty()) {
            errors.add("provider [" + name + "] supported_api_types 非法值: " + t
                + "（合法: " + ApiType.legalValues() + "）");
          }
        }
      }
      providerApiTypes.put(name, apiTypes);
    }

    // ---- models(模型唯一校验 + upstream 引用校验)----
    if (models == null || models.isEmpty()) {
      errors.add("models 未配置");
      return;
    }

    Set<String> modelNames = new HashSet<>();

    for (int modelIndex = 0; modelIndex < models.size(); modelIndex++) {

      Model m = models.get(modelIndex);

      String name = m.getName();
      if (StringUtils.isBlank(name)) {
        errors.add("第 " + (modelIndex + 1) + " 个 model 缺少 name");
        continue;
      }
      if (!modelNames.add(name)) {
        errors.add("model name 重复: " + name);
      }

      // upstream 必填且至少一条：每条给出转发用的 provider 与上游真实模型名（转发时用它替换对外模型名）
      List<Upstream> upstreams = m.getUpstream();
      if (upstreams == null || upstreams.isEmpty()) {
        errors.add("model [" + name + "] 必须至少配置一个 upstream");
        upstreams = List.of();
      }
      else {
        for (int i = 0; i < upstreams.size(); i++) {
          Upstream u = upstreams.get(i);
          String idx = "upstream[" + (i + 1) + "]";
          if (u == null) {
            errors.add("model [" + name + "] " + idx + " 不能为空");
            continue;
          }
          if (StringUtils.isBlank(u.getProvider())) {
            errors.add("model [" + name + "] " + idx + " 缺少 provider");
            continue;
          }
          if (!providerNames.contains(u.getProvider())) {
            errors.add("model [" + name + "] " + idx + " 引用了不存在的 provider: " + u.getProvider());
            continue;
          }
          if (StringUtils.isBlank(u.getModel())) {
            errors.add("model [" + name + "] " + idx + " 缺少 model");
          }
        }
      }

      // lb_policy 可不填，默认 first；填了必须是合法值
      if (StringUtils.isBlank(m.getLbPolicy())) {
        m.setLbPolicy(LbPolicy.FIRST.wire());
      }
      else if (LbPolicy.parse(m.getLbPolicy()).isEmpty()) {
        errors.add("model [" + name + "] lb_policy 非法值: " + m.getLbPolicy()
            + "（合法: " + LbPolicy.legalValues() + "）");
      }

      // 路由校验：model 声明的能力必须由每条 upstream 的 provider 支持
      // （random/round_robin 下任意一条都可能被选中转发，少一条支持就是线上 4xx/5xx）
      String capability = m.getCapability();
      if (capability == null) {
        errors.add("model [" + name + "] 必须配置 capability（不可为空）");
        continue;
      }

      Capability capabilityEnum = Capability.parse(capability).orElse(null);

      if (capabilityEnum == null) {
        errors.add("model [" + name + "] capability 非法值: " + capability + "（合法: " + Capability.legalValues() + "）");
        continue;
      }

      for (Upstream u : upstreams) {
        if (u == null || StringUtils.isBlank(u.getProvider()) || !providerNames.contains(u.getProvider())) {
          continue; // 引用问题上面已逐条报过
        }
        String provider = u.getProvider();
        List<String> apiTypes = providerApiTypes.getOrDefault(provider, List.of());
        if (apiTypes.isEmpty()) {
          continue; // supported_api_types 缺失/为空在 provider 段已报过
        }
        if (capabilityEnum == Capability.RERANK && !apiTypes.contains(ApiType.RERANK.wire())) {
          errors.add("model [" + name + "] 声明 rerank 能力, 但 provider [" + provider
              + "] 的 supported_api_types 不含 " + ApiType.RERANK.wire());
        }
        if (capabilityEnum == Capability.EMBEDDINGS && !apiTypes.contains(ApiType.EMBEDDINGS.wire())) {
          errors.add("model [" + name + "] 声明 embeddings 能力, 但 provider [" + provider
              + "] 的 supported_api_types 不含 " + ApiType.EMBEDDINGS.wire());
        }
        if (capabilityEnum == Capability.CHAT
            && apiTypes.stream().noneMatch(t -> ApiType.parse(t).map(ApiType::isChat).orElse(false))) {
          errors.add("model [" + name + "] 声明 chat 能力, 但 provider [" + provider
              + "] 的 supported_api_types 至少需包含一种 chat 格式 (" + ApiType.legalChatValues() + ")");
        }
      }
    }
  }

  /**
   * 按对外模型名与本次请求的 apiType 找路由：先筛出 provider 声明支持该 apiType 的 upstream 候选，
   * 再按 model.lb_policy 从候选里选一条（缺省 first）。模型未知或无候选返回 null（不抛异常）。
   */
  public Route findModelAndProvider(String modelName, ApiType apiType) {
    // 根据 modelName 找到对应的 Model 配置
    Model model = models.stream().filter(m -> m.getName().equals(modelName)).findFirst().orElse(null);

    if (model == null || model.getUpstream() == null) {
      return null;
    }

    // 能力与 apiType 必须对得上；capability 非法/缺失解析不出枚举 => 两个分支都不成立（启动时已校验合法性）
    Capability capability = Capability.parse(model.getCapability()).orElse(null);
    if ((capability == Capability.RERANK && apiType == ApiType.RERANK)
        || (capability == Capability.EMBEDDINGS && apiType == ApiType.EMBEDDINGS)
        || (capability == Capability.CHAT && apiType.isChat())) {
      // 候选：provider 存在且 supported_api_types 声明了本次 apiType 的 upstream
      List<Upstream> candidates = model.getUpstream().stream()
          .filter(u -> u != null && !StringUtils.isBlank(u.getProvider()))
          .filter(u -> supportedApiTypes(u.getProvider()).contains(apiType.wire()))
          .toList();

      if (!candidates.isEmpty()) {
        Upstream chosen = chooseUpstream(model, candidates);
        Provider provider = providers.stream()
            .filter(p -> p.getName().equals(chosen.getProvider())).findFirst().orElse(null);
        return new Route(model, provider, chosen.getModel());
      }
    }

    return null;
  }

  /** 按 lb_policy 在候选里选一条：first 恒取首条、random 均匀随机、round_robin 按模型名轮询 */
  private Upstream chooseUpstream(Model model, List<Upstream> candidates) {
    LbPolicy policy = LbPolicy.parse(model.getLbPolicy()).orElse(LbPolicy.FIRST);
    return switch (policy) {
      case RANDOM -> candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
      case ROUND_ROBIN -> {
        int seq = roundRobinCounters.computeIfAbsent(model.getName(), key -> new AtomicInteger()).getAndIncrement();
        yield candidates.get(Math.floorMod(seq, candidates.size()));
      }
      case FIRST -> candidates.get(0);
    };
  }

  /** provider 的 supported_api_types；provider 不存在或未配置该字段时视为不支持任何 apiType（不传播 null） */
  private List<String> supportedApiTypes(String providerName) {
    return providers.stream()
        .filter(p -> p.getName().equals(providerName))
        .findFirst()
        .map(Provider::getSupportedApiTypes)
        .orElse(List.of());
  }

  /**
   * base_url 的 host 是否命中内置厂商域名（后缀匹配，含子域名）
   */
  private static boolean requiresApiKey(String baseUrl) {
    if (baseUrl == null) {
      return false;
    }
    String host;
    try {
      host = URI.create(baseUrl.trim()).getHost();
    }
    catch (IllegalArgumentException e) {
      return false; // URL 非法由运行期 500 + 审计路径兜底，此处只做 key 必填检查
    }
    if (host == null) {
      return false;
    }
    host = host.toLowerCase(Locale.ROOT);
    return KeyRequiredDomains.contains(host);
  }

  /**
   * 三层上游超时（秒）：连接 / 响应头（request_timeout） / body 空闲读（read_idle_timeout，含流式分片间隔）
   */
  @Setter
  @Getter
  @NoArgsConstructor
  @ToString
  public static class Server {
    private int port;
    private String host;
    private List<String> clientApiKeys;
    private Integer connectTimeoutSeconds;
    private Integer requestTimeoutSeconds;
    private Integer readIdleTimeoutSeconds;
  }

  @Setter
  @Getter
  @NoArgsConstructor
  @ToString
  public static class Provider {
    private String name;
    private String openaiBaseUrl;
    private String anthropicBaseUrl;
    private String apiKey;
    private List<String> supportedApiTypes;

    public Provider(String name, String openaiBaseUrl, String anthropicBaseUrl,
                    String apiKey, List<String> supportedApiTypes) {
      this.name = name;
      this.openaiBaseUrl = openaiBaseUrl;
      this.anthropicBaseUrl = anthropicBaseUrl;
      this.apiKey = apiKey;
      this.supportedApiTypes = supportedApiTypes;
    }

  }

  /**
   * upstream 必填且至少一条:每条 = 转发用的 provider + 发给上游的真实模型名（对外名是 name），
   * 多条时按 lb_policy 从中选一条转发
   */
  @Setter
  @Getter
  @NoArgsConstructor
  @ToString
  public static class Model {
    private String name;
    private List<Upstream> upstream;
    private String lbPolicy;
    private Integer maxTokens;
    private Integer contextLength;
    private String capability;

    public Model(String name, List<Upstream> upstream, String lbPolicy,
                 Integer maxTokens, Integer contextLength, String capability) {
      this.name = name;
      this.upstream = upstream;
      this.lbPolicy = lbPolicy;
      this.maxTokens = maxTokens;
      this.contextLength = contextLength;
      this.capability = capability;
    }

    /** 单 upstream 的便捷构造：lb_policy 留空，走 validateConfig 的默认值 first */
    public Model(String name, String provider, String upstreamModel,
                 Integer maxTokens, Integer contextLength, String capability) {
      this(name, List.of(new Upstream(provider, upstreamModel)), null, maxTokens, contextLength, capability);
    }

  }

  /**
   * 一条上游路由：provider 决定 base_url/api_key，model 是发给上游的真实模型名
   */
  @Setter
  @Getter
  @NoArgsConstructor
  @ToString
  public static class Upstream {
    private String provider;
    private String model;

    public Upstream(String provider, String model) {
      this.provider = provider;
      this.model = model;
    }

  }

  /**
   * findModelAndProvider 的返回:model 配置 + 命中的 provider + 按 lb_policy 选出的上游真实模型名
   */
  public record Route(Model model, Provider provider, String upstreamModel) {
  }
}