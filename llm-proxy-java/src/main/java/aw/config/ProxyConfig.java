package aw.config;

import aw.util.StringUtils;
import java.net.URI;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.yaml.snakeyaml.util.Tuple;


@Setter
@Getter
@NoArgsConstructor
@ToString
public class ProxyConfig {

  private Server server;
  private List<Provider> providers;
  private List<Model> models;

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

    // ---- models(模型唯一校验 + provider 引用校验)----
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
      String provider = m.getProvider();
      if (StringUtils.isBlank(provider)) {
        errors.add("model [" + name + "] 缺少 provider");
      }
      else if (!providerNames.contains(provider)) {
        errors.add("model [" + name + "] 引用了不存在的 provider: " + provider);
      }
      // upstream 必填：转发时用它替换客户端传入的对外模型名
      if (StringUtils.isBlank(m.getUpstream())) {
        errors.add("model [" + name + "] 缺少 upstream");
      }
      // 路由校验：model 声明的能力必须有 provider 对应支持（supported_api_types 已校验非空）
      String capability = m.getCapability();
      if (capability == null) {
        errors.add("model [" + name + "] 必须配置 capability（不可为空）");
        continue;
      }

      Capability capabilityEnum = Capability.parse(capability).orElse(null);

      if (capabilityEnum == null) {
        errors.add("model [" + name + "] capability 非法值: " + capability + "（合法: " + Capability.legalValues() + "）");
      }

      List<String> apiTypes = providerApiTypes.getOrDefault(provider, List.of());
      if (!apiTypes.isEmpty()) {
        if (capabilityEnum == Capability.RERANK && !apiTypes.contains(ApiType.RERANK.wire())) {
          errors.add("model [" + name + "] 声明 rerank 能力, 但 provider [" + provider
              + "] 的 supported_api_types 不含 " + ApiType.RERANK.wire());
        }
        if (capabilityEnum  == Capability.EMBEDDINGS && !apiTypes.contains(ApiType.EMBEDDINGS.wire())) {
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

  public Tuple<Model, Provider> findModelAndProvider(String modelName, ApiType apiType) {
    // 根据 modelName 找到对应的 Model 配置
    Model model = models.stream().filter(m -> m.getName().equals(modelName)).findFirst().orElse(null);

    if (model == null) {
      return null;
    }

    // 判断当前模型是否支持 apiType, 启动时已经校验了，这里不判空
    Capability capability = Capability.parse(model.getCapability()).orElse(null);

    if ((capability == Capability.RERANK && apiType == ApiType.RERANK) || (capability == Capability.EMBEDDINGS && apiType == ApiType.EMBEDDINGS)) {
      return new Tuple<>(model, providers.stream()
          .filter(p -> p.getName().equals(model.getProvider())).findFirst().orElse(null));
    }
    else if (capability == Capability.CHAT && apiType.isChat()) {
      Provider provider = providers.stream()
          .filter(p -> p.getName().equals(model.getProvider())).findFirst().orElse(null);

      if (provider.getSupportedApiTypes().contains(apiType.wire())) {
        return new Tuple<>(model, provider);
      }
    }

    return null;
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
   * upstream 必填:转发给上游的真实模型名（对外名是 name）
   */
  @Setter
  @Getter
  @NoArgsConstructor
  @ToString
  public static class Model {
    private String name;
    private String provider;
    private String upstream;
    private Integer maxTokens;
    private Integer contextLength;
    private String capability;

    public Model(String name, String provider, String upstream,
                 Integer maxTokens, Integer contextLength, String capability) {
      this.name = name;
      this.provider = provider;
      this.upstream = upstream;
      this.maxTokens = maxTokens;
      this.contextLength = contextLength;
      this.capability = capability;
    }

  }
}