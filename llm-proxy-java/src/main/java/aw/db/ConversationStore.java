package aw.db;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 对话记录存储。
 * 每次代理请求写一条 conversations（api 区分来源接口），请求 messages 数组逐条写 messages。
 * 表结构见 resources/schema.sql，构造时自动执行（幂等，CREATE IF NOT EXISTS），
 * 并为旧库补齐新增列（ALTER TABLE ADD COLUMN，无则跳过）。
 */
public class ConversationStore {

  private static final Logger log = LoggerFactory.getLogger(ConversationStore.class);

  /** 请求里的一条消息 */
  public record ChatMessage(int seq, String role, String content) {}

  private static final String INSERT_CONVERSATION = """
      INSERT INTO conversations(api, session_id, model, stream, status_code, request_body, response_body,
                                prompt_tokens, completion_tokens, total_tokens, cache_tokens, latency_ms, error_message)
      VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """;

  private static final String INSERT_MESSAGE = """
      INSERT INTO messages(conversation_id, seq, role, content) VALUES(?, ?, ?, ?)
      """;

  private final String jdbcUrl;

  public ConversationStore(String dbPath) {
    this.jdbcUrl = "jdbc:sqlite:" + dbPath;
    initSchema();
  }

  private void initSchema() {
    try (Connection conn = DriverManager.getConnection(jdbcUrl);
        Statement st = conn.createStatement()) {
      st.execute("PRAGMA journal_mode=WAL");
      String schema = readResource("/schema.sql");
      for (String stmt : schema.split(";")) {
        if (!stmt.isBlank()) {
          st.execute(stmt);
        }
      }
      // 旧库升级：SQLite 的 ALTER TABLE 不支持 IF NOT EXISTS，先查 pragma 再补列
      addColumnIfMissing(conn, "conversations", "api", "TEXT");
      addColumnIfMissing(conn, "conversations", "cache_tokens", "INTEGER");
    } catch (Exception e) {
      throw new IllegalStateException("初始化对话记录表失败", e);
    }
  }

  private static void addColumnIfMissing(Connection conn, String table, String column, String type) throws SQLException {
    boolean exists;
    try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM pragma_table_info(?) WHERE name = ?")) {
      ps.setString(1, table);
      ps.setString(2, column);
      try (ResultSet rs = ps.executeQuery()) {
        exists = rs.next();
      }
    }
    if (!exists) {
      try (Statement st = conn.createStatement()) {
        st.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
        log.info("已为表 {} 补充列 {} {}", table, column, type);
      }
    }
  }

  /**
   * 写入一次请求的完整记录（单事务）。
   * 数据库异常只记日志不上抛——记录失败不能影响代理转发。
   */
  public void log(String api, String sessionId, String model, boolean stream, Integer statusCode,
      String requestBody, String responseBody, Integer promptTokens, Integer completionTokens,
      Integer totalTokens, Integer cacheTokens, Long latencyMs, String errorMessage, List<ChatMessage> messages) {
    try (Connection conn = open()) {
      conn.setAutoCommit(false);
      long conversationId;
      try (PreparedStatement ps = conn.prepareStatement(INSERT_CONVERSATION, Statement.RETURN_GENERATED_KEYS)) {
        ps.setString(1, api);
        ps.setString(2, sessionId);
        ps.setString(3, model);
        ps.setInt(4, stream ? 1 : 0);
        if (statusCode != null) {
          ps.setInt(5, statusCode);
        } else {
          ps.setNull(5, java.sql.Types.INTEGER);
        }
        ps.setString(6, requestBody);
        ps.setString(7, responseBody);
        setNullableInt(ps, 8, promptTokens);
        setNullableInt(ps, 9, completionTokens);
        setNullableInt(ps, 10, totalTokens);
        setNullableInt(ps, 11, cacheTokens);
        if (latencyMs != null) {
          ps.setLong(12, latencyMs);
        } else {
          ps.setNull(12, java.sql.Types.INTEGER);
        }
        ps.setString(13, errorMessage);
        ps.executeUpdate();
        try (ResultSet keys = ps.getGeneratedKeys()) {
          conversationId = keys.next() ? keys.getLong(1) : -1;
        }
      }
      if (conversationId > 0 && messages != null && !messages.isEmpty()) {
        try (PreparedStatement pm = conn.prepareStatement(INSERT_MESSAGE)) {
          for (ChatMessage m : messages) {
            pm.setLong(1, conversationId);
            pm.setInt(2, m.seq());
            pm.setString(3, m.role());
            pm.setString(4, m.content());
            pm.addBatch();
          }
          pm.executeBatch();
        }
      }
      conn.commit();
    } catch (SQLException e) {
      log.warn("写入对话记录失败: {}", e.getMessage(), e);
    }
  }

  private Connection open() throws SQLException {
    Connection conn = DriverManager.getConnection(jdbcUrl);
    try (Statement st = conn.createStatement()) {
      st.execute("PRAGMA foreign_keys=ON");
      st.execute("PRAGMA busy_timeout=5000");
    }
    return conn;
  }

  private static void setNullableInt(PreparedStatement ps, int index, Integer value) throws SQLException {
    if (value != null) {
      ps.setInt(index, value);
    } else {
      ps.setNull(index, java.sql.Types.INTEGER);
    }
  }

  private static String readResource(String path) throws IOException {
    try (InputStream in = ConversationStore.class.getResourceAsStream(path)) {
      if (in == null) {
        throw new IOException("classpath 中找不到 " + path);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
