package aw.auth;

import io.javalin.http.Context;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * 客户端 API Key 鉴权（server.client_api_keys）。
 * client_api_keys 非空时由 Server 注册的 before 过滤器调用，缺失或非法返回 401；
 * 为空则不注册过滤器（不鉴权）。
 */
public final class ClientAuth {

  /** before 过滤器解析出的客户端 key，handler 落库时从这里取 */
  public static final String CLIENT_KEY_ATTR = "clientApiKey";

  private ClientAuth() {}

  /** 从 Context 提取 Authorization 头并解析 Bearer token */
  public static String bearerToken(Context ctx) {
    return bearerToken(ctx.header("Authorization"));
  }

  /** 从 Authorization: Bearer <token> 提取 token，缺失或格式不符返回 null（前缀大小写不敏感） */
  public static String bearerToken(String authorizationHeader) {
    if (authorizationHeader == null) {
      return null;
    }
    String prefix = "Bearer ";
    if (authorizationHeader.length() <= prefix.length()
        || !authorizationHeader.regionMatches(true, 0, prefix, 0, prefix.length())) {
      return null;
    }
    return authorizationHeader.substring(prefix.length()).trim();
  }

  /** 恒定时间比较（遍历全部候选 key），避免时序侧信道 */
  public static boolean matches(String token, List<String> validKeys) {
    if (token == null || validKeys == null || validKeys.isEmpty()) {
      return false;
    }
    byte[] given = token.getBytes(StandardCharsets.UTF_8);
    boolean ok = false;
    for (String key : validKeys) {
      ok |= MessageDigest.isEqual(given, key.getBytes(StandardCharsets.UTF_8));
    }
    return ok;
  }
}
