package aw.config;

import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * provider.supported_api_types 的合法取值（封闭集合）。
 * 配置文件里写的是 {@link #wire()} 字面值，这里只做校验期解析——
 * ProxyConfig 的字段仍保持 List&lt;String&gt;，避免 fastjson2 在反序列化阶段
 * 遇到非法值直接抛异常，破坏"启动时一次性报出所有配置错误"的 fail-fast 契约。
 */
public enum ApiType {

  OPENAI_CHAT_COMPLETIONS("openai.chat.completions", true),
  OPENAI_RESPONSES("openai.responses", true),
  ANTHROPIC_MESSAGES("anthropic.messages", true),
  EMBEDDINGS("embeddings", false),
  RERANK("rerank", false);

  private final String wire;

  /** 是否属于 chat 类格式（三种对话接口之一） */
  private final boolean chat;

  ApiType(String wire, boolean chat) {
    this.wire = wire;
    this.chat = chat;
  }

  /** 配置文件里写的字面值（精确匹配，区分大小写） */
  public String wire() {
    return wire;
  }

  /** 是否 chat 类格式：model 声明 chat 能力时，provider 至少要有一个 chat 格式 */
  public boolean isChat() {
    return chat;
  }

  /** 按配置字面值解析；非法或 null 返回 empty（区分大小写，与历史行为一致） */
  public static Optional<ApiType> parse(String raw) {
    if (raw == null) {
      return Optional.empty();
    }
    for (ApiType type : values()) {
      if (type.wire.equals(raw)) {
        return Optional.of(type);
      }
    }
    return Optional.empty();
  }

  /** 全部合法值的提示文案，顺序即枚举声明顺序 */
  public static String legalValues() {
    return join(Arrays.stream(values()));
  }

  /** 仅 chat 类合法值的提示文案 */
  public static String legalChatValues() {
    return join(Arrays.stream(values()).filter(ApiType::isChat));
  }

  private static String join(java.util.stream.Stream<ApiType> stream) {
    return stream.map(ApiType::wire).collect(Collectors.joining(" / "));
  }
}
