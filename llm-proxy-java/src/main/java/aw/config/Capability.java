package aw.config;

import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * model.capability 的合法取值（配置里写单个 {@link #wire()} 字面值，精确匹配、区分大小写）。
 * 字段是单个 String 而不是集合，因此只能在校验期解析——非法值在 validateConfig 里一次性报错，
 * 不在 fastjson2 反序列化阶段抛异常，保持"启动时一次报出所有配置错误"的 fail-fast 契约。
 * 原因同样见 {@link ApiType} 的说明。
 */
public enum Capability {

  CHAT("chat"),
  EMBEDDINGS("embeddings"),
  RERANK("rerank");

  private final String wire;

  Capability(String wire) {
    this.wire = wire;
  }

  /** 配置文件里写的字面值（精确匹配，区分大小写） */
  public String wire() {
    return wire;
  }

  /** 按配置字面值解析；非法或 null 返回 empty（区分大小写，与历史行为一致） */
  public static Optional<Capability> parse(String raw) {
    if (raw == null) {
      return Optional.empty();
    }
    for (Capability capability : values()) {
      if (capability.wire.equals(raw)) {
        return Optional.of(capability);
      }
    }
    return Optional.empty();
  }

  /** 全部合法值的提示文案，顺序即枚举声明顺序 */
  public static String legalValues() {
    return Arrays.stream(values())
        .map(Capability::wire)
        .collect(Collectors.joining(" / "));
  }

  /** 便于日志/提示：输出配置里的字面值（小写）而不是枚举名 */
  @Override
  public String toString() {
    return wire;
  }
}
