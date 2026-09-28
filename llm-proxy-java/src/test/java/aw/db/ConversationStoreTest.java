package aw.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aw.db.ConversationStore.ChatMessage;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 审计落库核心路径：写入 + 消息展开 + 旧库迁移补列 */
class ConversationStoreTest {

  @Test
  void log写入conversation与messages(@TempDir Path tmp) throws Exception {
    ConversationStore store = new ConversationStore(tmp.resolve("t.db").toString());
    store.log("openai.chat.completions", "sess-1", "client-key", "m1", "p1", true, 200,
        "{\"model\":\"m1\"}", "{\"ok\":true}", 10, 20, 30, 4, 123L, null,
        List.of(new ChatMessage(0, "user", "hi"), new ChatMessage(1, "assistant", "ho")));

    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + tmp.resolve("t.db"));
        Statement st = c.createStatement()) {
      try (ResultSet rs = st.executeQuery(
          "SELECT api, session_id, client_api_key, model, provider, stream, status_code, prompt_tokens, cache_tokens FROM conversations")) {
        assertTrue(rs.next());
        assertEquals("openai.chat.completions", rs.getString(1));
        assertEquals("sess-1", rs.getString(2));
        assertEquals("client-key", rs.getString(3));
        assertEquals("m1", rs.getString(4));
        assertEquals("p1", rs.getString(5));
        assertEquals(1, rs.getInt(6));
        assertEquals(200, rs.getInt(7));
        assertEquals(10, rs.getInt(8));
        assertEquals(4, rs.getInt(9));
      }
      try (ResultSet rs = st.executeQuery("SELECT seq, role, content FROM messages ORDER BY seq")) {
        assertTrue(rs.next());
        assertEquals(0, rs.getInt(1));
        assertEquals("user", rs.getString(2));
        assertEquals("hi", rs.getString(3));
        assertTrue(rs.next());
        assertEquals("assistant", rs.getString(2));
      }
    }
  }

  @Test
  void 失败请求null字段落库(@TempDir Path tmp) throws Exception {
    ConversationStore store = new ConversationStore(tmp.resolve("t.db").toString());
    store.log("rerank", null, null, "m1", null, false, null,
        "{}", null, null, null, null, null, null, "boom", null);
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + tmp.resolve("t.db"));
        Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("SELECT status_code, error_message, stream FROM conversations")) {
      assertTrue(rs.next());
      assertEquals(0, rs.getInt(1));
      assertTrue(rs.wasNull());
      assertEquals("boom", rs.getString(2));
      assertEquals(0, rs.getInt(3));
    }
  }

  @Test
  void 旧库自动补列(@TempDir Path tmp) throws Exception {
    String db = tmp.resolve("legacy.db").toString();
    // 建一个没有 api/cache_tokens/provider/client_api_key 列的旧库
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
        Statement st = c.createStatement()) {
      st.execute("CREATE TABLE conversations (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT, model TEXT NOT NULL, stream INTEGER NOT NULL DEFAULT 0, status_code INTEGER, request_body TEXT, response_body TEXT, prompt_tokens INTEGER, completion_tokens INTEGER, total_tokens INTEGER, latency_ms INTEGER, error_message TEXT, created_at TEXT NOT NULL DEFAULT (datetime('now','localtime')))");
      st.execute("INSERT INTO conversations(session_id, model) VALUES ('old', 'legacy-model')");
    }
    // 实例化触发迁移
    new ConversationStore(db);
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
        Statement st = c.createStatement();
        ResultSet rs = st.executeQuery(
            "SELECT api, cache_tokens, provider, client_api_key FROM conversations WHERE session_id='old'")) {
      assertTrue(rs.next());
      // 迁移只补列不补数据，旧行新列为 NULL
      assertNull(rs.getString("api"));
      assertTrue(rs.wasNull());
    }
  }
}
