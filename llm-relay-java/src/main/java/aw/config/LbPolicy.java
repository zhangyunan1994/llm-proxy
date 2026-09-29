package aw.config;

import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * model.lb_policy 的合法取值：模型配置多条 upstream 时的负载均衡策略（配置里可不填，默认 {@link #FIRST}）。
 * 与 {@link Capability} 同样只在校验期解析——非法值在 validateConfig 里一次性报错，
 * 不在 fastjson2 反序列化阶段抛异常，保持"启动时一次报出所有配置错误"的 fail-fast 契约。
 */
public enum LbPolicy {

  /** 恒取 upstream 首条（默认） */
  FIRST("first"),
  /** 在候选 upstream 里均匀随机 */
  RANDOM("random"),
  /** 按模型名各自轮询候选 upstream（进程内计数，重启归零） */
  ROUND_ROBIN("round_robin");

  private final String wire;

  LbPolicy(String wire) {
    this.wire = wire;
  }

  /** 配置文件里写的字面值（精确匹配，区分大小写） */
  public String wire() {
    return wire;
  }

  /** 按配置字面值解析；非法或 null 返回 empty（区分大小写，与 {@link Capability} 一致） */
  public static Optional<LbPolicy> parse(String raw) {
    if (raw == null) {
      return Optional.empty();
    }
    for (LbPolicy policy : values()) {
      if (policy.wire.equals(raw)) {
        return Optional.of(policy);
      }
    }
    return Optional.empty();
  }

  /** 全部合法值的提示文案，顺序即枚举声明顺序 */
  public static String legalValues() {
    return Arrays.stream(values())
        .map(LbPolicy::wire)
        .collect(Collectors.joining(" / "));
  }

  /** 便于日志/提示：输出配置里的字面值（小写）而不是枚举名 */
  @Override
  public String toString() {
    return wire;
  }
}
