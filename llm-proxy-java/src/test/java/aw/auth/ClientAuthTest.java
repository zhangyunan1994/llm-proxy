package aw.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 客户端鉴权核心路径：Bearer 解析 + 恒定时间匹配 */
class ClientAuthTest {

  @Test
  void bearerToken解析标准头() {
    assertEquals("sk-abc", ClientAuth.bearerToken("Bearer sk-abc"));
    assertEquals("sk-abc", ClientAuth.bearerToken("bearer sk-abc"));
    assertEquals("sk-abc", ClientAuth.bearerToken("BEARER   sk-abc  "));
  }

  @Test
  void bearerToken拒绝缺失与畸形头() {
    assertNull(ClientAuth.bearerToken((String) null));
    assertNull(ClientAuth.bearerToken(""));
    assertNull(ClientAuth.bearerToken("Bearer"));
    assertNull(ClientAuth.bearerToken("Bearer "));
    assertNull(ClientAuth.bearerToken("Basic dXNlcjpwYXNz"));
    assertNull(ClientAuth.bearerToken("sk-abc"));
  }

  @Test
  void matches命中与拒绝() {
    List<String> keys = List.of("key-one", "key-two");
    assertTrue(ClientAuth.matches("key-one", keys));
    assertTrue(ClientAuth.matches("key-two", keys));
    assertFalse(ClientAuth.matches("key-three", keys));
    assertFalse(ClientAuth.matches(null, keys));
    assertFalse(ClientAuth.matches("key-one", null));
    assertFalse(ClientAuth.matches("key-one", List.of()));
    // 空串 token 不等于空串 key 的列表（空条目已被配置校验拒绝）
    assertFalse(ClientAuth.matches("", keys));
  }
}
