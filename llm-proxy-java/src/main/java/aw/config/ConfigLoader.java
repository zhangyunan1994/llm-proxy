package aw.config;

import aw.util.StringUtils;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

/**
 * config.yaml 加载与校验。
 * 查找顺序: 1) -Dllm-proxy.config=&lt;path&gt;  2) 当前目录 config.yaml  3) classpath /config.yaml
 * 结构非法时抛 ConfigException(fail fast),一次报出所有错误。
 */
public final class ConfigLoader {

  private static final Logger log = LoggerFactory.getLogger(ConfigLoader.class);

  private ConfigLoader() {}

  public static final class ConfigException extends RuntimeException {
    public ConfigException(List<String> errors) {
      super("配置校验失败:\n  - " + String.join("\n  - ", errors));
    }
  }

  public record ProxyConfig(Server server, List<Provider> providers, List<Model> models) {

    public record Server(int port, List<String> clientApiKeys) {}

    public record Provider(String name, String openaiBaseUrl, String anthropicBaseUrl,
        String apiKey, List<String> supportedApiTypes) {}

    /** upstream 已在加载时兜底:省略时等于 name */
    public record Model(String name, String provider, String upstream,
        Integer maxTokens, Integer contextLength, List<String> capabilities) {}
  }

  /** 按默认顺序查找并加载配置 */
  public static ProxyConfig load() {
    String prop = System.getProperty("llm-proxy.config");
    if (prop != null && !prop.isBlank()) {
      return load(Path.of(prop.trim()));
    }
    Path cwd = Path.of("config.yaml");
    if (Files.isReadable(cwd)) {
      return load(cwd);
    }
    InputStream in = ConfigLoader.class.getResourceAsStream("/config.yaml");
    if (in != null) {
      log.info("使用 classpath 配置文件: /config.yaml");
      return parse(readAll(in), "classpath:/config.yaml");
    }
    throw new ConfigException(
        List.of("找不到配置文件(尝试过 -Dllm-proxy.config、./config.yaml、classpath:/config.yaml)"));
  }

  public static ProxyConfig load(Path path) {
    String content;
    try {
      content = Files.readString(path);
    } catch (IOException e) {
      throw new ConfigException(List.of("读取配置文件失败 " + path.toAbsolutePath() + ": " + e.getMessage()));
    }
    log.info("使用配置文件: {}", path.toAbsolutePath());
    return parse(content, path.toString());
  }

  public static ProxyConfig parse(String content, String source) {
    Object root;
    try {
      root = new Yaml().load(content);
    } catch (Exception e) {
      throw new ConfigException(List.of(source + " YAML 语法错误: " + e.getMessage()));
    }
    if (!(root instanceof Map<?, ?> map)) {
      throw new ConfigException(List.of(source + " 顶层必须是映射"));
    }

    List<String> errors = new ArrayList<>();
    List<String> warnings = new ArrayList<>();

    // ---- server ----
    Map<?, ?> serverMap = map.get("server") instanceof Map<?, ?> s ? s : Map.of();
    int port = 8080;
    Object addr = serverMap.get("addr");
    if (addr == null) {
      warnings.add("server.addr 未配置,使用默认端口 8080");
    } else {
      String raw = String.valueOf(addr).trim();
      if (raw.startsWith(":")) {
        raw = raw.substring(1);
      }
      try {
        port = Integer.parseInt(raw);
        if (port < 1 || port > 65535) {
          errors.add("server.addr 端口超出范围(1-65535): " + addr);
        }
      } catch (NumberFormatException e) {
        errors.add("server.addr 必须是端口数字(支持 8080 / \":8080\"),实际: " + addr);
      }
    }
    List<String> clientApiKeys = strList(serverMap, "client_api_keys");
    for (int i = 0; i < clientApiKeys.size(); i++) {
      if (StringUtils.isBlank(clientApiKeys.get(i))) {
        errors.add("server.client_api_keys[" + i + "] 为空");
      }
    }

    // ---- providers(厂商唯一校验)----
    List<ProxyConfig.Provider> providers = new ArrayList<>();
    Set<String> providerNames = new HashSet<>();
    if (map.get("providers") instanceof List<?> list) {
      for (int i = 0; i < list.size(); i++) {
        if (!(list.get(i) instanceof Map<?, ?> p)) {
          errors.add("providers[" + i + "] 不是映射");
          continue;
        }
        String name = str(p, "name");
        if (StringUtils.isBlank(name)) {
          errors.add("providers[" + i + "] 缺少 name");
          continue;
        }
        if (!providerNames.add(name)) {
          errors.add("provider name 重复: " + name);
        }
        if (StringUtils.isBlank(str(p, "openai_base_url")) && StringUtils.isBlank(str(p, "anthropic_base_url"))) {
          warnings.add("provider [" + name + "] 未配置任何 base_url");
        }
        providers.add(new ProxyConfig.Provider(name, str(p, "openai_base_url"), str(p, "anthropic_base_url"),
            str(p, "api_key"), strList(p, "supported_api_types")));
      }
    } else {
      warnings.add("providers 未配置");
    }

    // ---- models(模型唯一校验 + provider 引用校验)----
    List<ProxyConfig.Model> models = new ArrayList<>();
    Set<String> modelNames = new HashSet<>();
    if (map.get("models") instanceof List<?> list) {
      for (int i = 0; i < list.size(); i++) {
        if (!(list.get(i) instanceof Map<?, ?> m)) {
          errors.add("models[" + i + "] 不是映射");
          continue;
        }
        String name = str(m, "name");
        if (StringUtils.isBlank(name)) {
          errors.add("models[" + i + "] 缺少 name");
          continue;
        }
        if (!modelNames.add(name)) {
          errors.add("model name 重复: " + name);
        }
        String provider = str(m, "provider");
        if (StringUtils.isBlank(provider)) {
          errors.add("model [" + name + "] 缺少 provider");
        } else if (!providerNames.contains(provider)) {
          errors.add("model [" + name + "] 引用了不存在的 provider: " + provider);
        }
        String upstream = str(m, "upstream");
        if (StringUtils.isBlank(upstream)) {
          upstream = name;
        }
        models.add(new ProxyConfig.Model(name, provider, upstream,
            positiveInt(m.get("max_tokens"), "model [" + name + "].max_tokens", errors),
            positiveInt(m.get("context_length"), "model [" + name + "].context_length", errors),
            strList(m, "capabilities")));
      }
    } else {
      warnings.add("models 未配置");
    }

    if (!errors.isEmpty()) {
      throw new ConfigException(errors);
    }
    warnings.forEach(w -> log.warn("配置警告: {}", w));
    return new ProxyConfig(new ProxyConfig.Server(port, clientApiKeys),
        List.copyOf(providers), List.copyOf(models));
  }

  private static String str(Map<?, ?> m, String key) {
    Object v = m.get(key);
    return v == null ? null : String.valueOf(v).trim();
  }

  private static Integer positiveInt(Object v, String field, List<String> errors) {
    if (v == null) {
      return null;
    }
    if (v instanceof Number n && n.longValue() > 0 && n.longValue() <= Integer.MAX_VALUE) {
      return n.intValue();
    }
    errors.add(field + " 必须为正整数,实际: " + v);
    return null;
  }

  private static List<String> strList(Map<?, ?> m, String key) {
    if (!(m.get(key) instanceof List<?> list)) {
      return List.of();
    }
    return list.stream().filter(Objects::nonNull)
        .map(v -> String.valueOf(v).trim())
        .filter(s -> !s.isEmpty())
        .toList();
  }

  private static String readAll(InputStream in) {
    try (in) {
      return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new ConfigException(List.of("读取配置文件失败: " + e.getMessage()));
    }
  }
}
