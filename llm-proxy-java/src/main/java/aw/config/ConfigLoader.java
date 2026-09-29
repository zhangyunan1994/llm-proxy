package aw.config;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONReader.Feature;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

/**
 * config.yaml 加载与校验。 查找顺序: 1) -Dllm-proxy.config=&lt;path&gt;  2) 当前目录 config.yaml  3) classpath /config.yaml 结构非法时抛
 * ConfigException(fail fast),一次报出所有错误。
 */
public final class ConfigLoader {

  private static final Logger log = LoggerFactory.getLogger(ConfigLoader.class);

  private ConfigLoader() {
  }

  public static final class ConfigException extends RuntimeException {

    public ConfigException(List<String> errors) {
      super("配置校验失败:\n  - " + String.join("\n  - ", errors));
    }
  }

  /**
   * 按默认顺序查找并加载配置
   */
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
    }
    catch (IOException e) {
      throw new ConfigException(List.of("读取配置文件失败 " + path.toAbsolutePath() + ": " + e.getMessage()));
    }
    log.info("使用配置文件: {}", path.toAbsolutePath());
    return parse(content, path.toString());
  }

  public static ProxyConfig parse(String content, String source) {
    Object root;
    try {
      root = new Yaml().load(content);
    }
    catch (Exception e) {
      throw new ConfigException(List.of(source + " YAML 语法错误: " + e.getMessage()));
    }
    if (!(root instanceof Map<?, ?> map)) {
      throw new ConfigException(List.of(source + " 顶层必须是映射"));
    }

    ProxyConfig proxyConfig = JSON.parseObject(JSON.toJSONString(root), ProxyConfig.class, Feature.SupportSmartMatch);

    List<String> errors = new ArrayList<>();
    List<String> warnings = new ArrayList<>();

    if (proxyConfig == null) {
      errors.add("配置对象为空");
    }
    else {
      proxyConfig.validateConfig(errors, warnings);
    }

    if (!errors.isEmpty()) {
      throw new ConfigException(errors);
    }
    warnings.forEach(w -> log.warn("配置警告: {}", w));
    return proxyConfig;
  }

  private static String readAll(InputStream in) {
    try (in) {
      return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }
    catch (IOException e) {
      throw new ConfigException(List.of("读取配置文件失败: " + e.getMessage()));
    }
  }


}
