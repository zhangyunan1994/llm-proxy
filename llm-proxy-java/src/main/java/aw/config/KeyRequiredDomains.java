package aw.config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 内置直连厂商域名清单：base_url 的 host 命中这些域名（含子域名）时必须配置 api_key。
 * 数据来自 classpath:/key-required-domains.txt（每行一个域名，空行与 # 注释忽略）——
 * 新增厂商只需改资源文件，不必动代码。
 */
final class KeyRequiredDomains {

  private static final String RESOURCE = "/key-required-domains.txt";

  private static final Set<String> DOMAINS = load();

  private KeyRequiredDomains() {
  }

  /** host（需已转小写）是否命中内置厂商域名：精确匹配或以 .域名 结尾（防 evildeepseek.com 这类绕过） */
  static boolean contains(String host) {
    for (String domain : DOMAINS) {
      if (host.equals(domain) || host.endsWith("." + domain)) {
        return true;
      }
    }
    return false;
  }

  /** 清单条目数（供启动日志/测试确认资源真的加载到了） */
  static int size() {
    return DOMAINS.size();
  }

  private static Set<String> load() {
    try (InputStream in = KeyRequiredDomains.class.getResourceAsStream(RESOURCE)) {
      if (in == null) {
        // 缺资源属于构建/打包问题，启动即失败，不要静默退化成"永不校验"
        throw new IllegalStateException("classpath 中找不到 " + RESOURCE);
      }
      try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
        return reader.lines()
            .map(String::trim)
            .filter(line -> !line.isEmpty() && !line.startsWith("#"))
            .collect(Collectors.toUnmodifiableSet());
      }
    } catch (IOException e) {
      throw new IllegalStateException("读取 " + RESOURCE + " 失败", e);
    }
  }
}
