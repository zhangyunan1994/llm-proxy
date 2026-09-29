package aw;

import static org.junit.jupiter.api.Assertions.assertEquals;

import aw.config.ProxyConfig;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** DB 路径三级优先级：-Dllm-relay.db > server.db_path > $HOME/.config/llm-relay/llm-relay.db */
class ServerTest {

  private String previousDbProperty;

  @BeforeEach
  void clearDbProperty() {
    // 用例之间不能互相污染系统属性
    previousDbProperty = System.getProperty("llm-relay.db");
    System.clearProperty("llm-relay.db");
  }

  @AfterEach
  void restoreDbProperty() {
    if (previousDbProperty == null) {
      System.clearProperty("llm-relay.db");
    }
    else {
      System.setProperty("llm-relay.db", previousDbProperty);
    }
  }

  @Test
  void prefersSystemPropertyOverConfiguredDbPath(@TempDir Path tmp) {
    Path given = tmp.resolve("from-property.db");
    System.setProperty("llm-relay.db", given.toString());

    assertEquals(given.toString(), Server.resolveDbPath(configWithDbPath("/from/config/db.sqlite")));
  }

  @Test
  void usesServerDbPathWhenPropertyAbsent(@TempDir Path tmp) {
    Path given = tmp.resolve("from-config.db");

    assertEquals(given.toString(), Server.resolveDbPath(configWithDbPath(given.toString())));
  }

  @Test
  void ignoresBlankSystemProperty(@TempDir Path tmp) {
    // 只有空白的 -Dllm-relay.db 视为未设置，继续走 config.yaml
    System.setProperty("llm-relay.db", "   ");
    Path given = tmp.resolve("from-config.db");

    assertEquals(given.toString(), Server.resolveDbPath(configWithDbPath(given.toString())));
  }

  @Test
  void fallsBackToHomeDefaultWhenUnconfigured() {
    String expected = Path.of(System.getProperty("user.home"), ".config", "llm-relay", "llm-relay.db").toString();

    assertEquals(expected, Server.resolveDbPath(configWithDbPath(null)));
    assertEquals(expected, Server.resolveDbPath(configWithDbPath("  ")));
  }

  @Test
  void treatsDirectoryDbPathAsDirectory(@TempDir Path tmp) {
    // 目录写法（已存在的目录、以 / 结尾、. 与 ..）=> 库落在该目录下的 llm-relay.db
    String expected = tmp.resolve("llm-relay.db").toString();
    assertEquals(expected, Server.resolveDbPath(configWithDbPath(tmp.toString() + "/")));
    assertEquals(expected, Server.resolveDbPath(configWithDbPath(tmp.toString())));
    assertEquals("./llm-relay.db", Server.resolveDbPath(configWithDbPath("./")));
    assertEquals("./llm-relay.db", Server.resolveDbPath(configWithDbPath(".")));
  }

  @Test
  void treatsDirectoryPropertyAsDirectory(@TempDir Path tmp) {
    System.setProperty("llm-relay.db", tmp.toString() + "/");

    String expected = tmp.resolve("llm-relay.db").toString();
    assertEquals(expected, Server.resolveDbPath(configWithDbPath(null)));
  }

  private static ProxyConfig configWithDbPath(String dbPath) {
    ProxyConfig.Server server = new ProxyConfig.Server();
    server.setDbPath(dbPath);
    return new ProxyConfig(server, List.of(), List.of());
  }
}
