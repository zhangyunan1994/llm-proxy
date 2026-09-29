package aw.httphandler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aw.db.ConversationStore;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 看门狗与消息解析核心路径（均为历轮实测修过的 bug 回归） */
class UpstreamHttpClientTest {

  @Test
  void watchdogUnblocksReadOnIdleTimeout() throws Exception {
    try (ServerSocket server = new ServerSocket(0)) {
      Thread upstream = new Thread(() -> {
        try (Socket sock = server.accept()) {
          // 请求体随连接持续存在（HttpClient 不会半关），读一次缓冲即可
          sock.getInputStream().read(new byte[1024]);
          // 合法响应头 + 声明 100 字节但只发 7 字节：响应头可被 HttpClient 接收，body 读取永久挂死
          sock.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\npartial").getBytes());
          sock.getOutputStream().flush();
          Thread.sleep(60_000);
        } catch (Exception ignored) {
        }
      });
      upstream.setDaemon(true);
      upstream.start();

      java.net.http.HttpResponse<InputStream> resp = java.net.http.HttpClient.newHttpClient().send(
          java.net.http.HttpRequest.newBuilder()
              .uri(java.net.URI.create("http://127.0.0.1:" + server.getLocalPort()))
              .timeout(Duration.ofSeconds(5))
              .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{}"))
              .build(),
          java.net.http.HttpResponse.BodyHandlers.ofInputStream());

      long t0 = System.currentTimeMillis();
      assertThrows(IOException.class, () -> {
        try (InputStream in = UpstreamHttpClient.withReadWatchdog(resp.body(), Duration.ofMillis(200))) {
          // 读到 partial 后上游挂死，200ms 空闲超时应解除阻塞
          while (in.read() != -1) {
            // 持续读
          }
        }
      });
      assertTrue(System.currentTimeMillis() - t0 < 5_000, "看门狗应在空闲超时后解除阻塞");
    }
  }

  @Test
  void parseMessagesToleratesMalformedChatMessages() {
    JSONObject json = JSON.parseObject("{\"messages\":[\"plain string\",{\"content\":\"no role\"},{\"role\":\"user\",\"content\":\"hi\"}]}");
    List<ConversationStore.ChatMessage> list = OpenAIChatCompletionsHttpHandler.parseMessages(json);
    assertEquals(2, list.size());
    assertEquals("unknown", list.get(0).role());
    assertEquals("no role", list.get(0).content());
    assertEquals("user", list.get(1).role());
  }

  @Test
  void includesAnthropicSystemFieldInMessages() {
    JSONObject json = JSON.parseObject(
        "{\"system\":\"你是助手\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
    List<ConversationStore.ChatMessage> list = AnthropicMessagesHttpHandler.parseMessages(json);
    assertEquals(2, list.size());
    assertEquals(-1, list.get(0).seq());
    assertEquals("system", list.get(0).role());
    assertEquals("你是助手", list.get(0).content());
    assertEquals("user", list.get(1).role());
  }

  @Test
  void joinsAnthropicSystemTextBlocks() {
    JSONObject json = JSON.parseObject(
        "{\"system\":[{\"type\":\"text\",\"text\":\"甲\"},{\"type\":\"text\",\"text\":\"乙\"}],\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
    List<ConversationStore.ChatMessage> list = AnthropicMessagesHttpHandler.parseMessages(json);
    assertEquals("system", list.get(0).role());
    assertEquals("甲\n乙", list.get(0).content());
  }

  @Test
  void capsRequestBodyAt100Mb() {
    // README「请求体上限 100MB」与 Server 的 Javalin maxRequestSize 共用此常量；
    // 曾因两处分别写 50MB/100MB 而不一致，故固定口径
    assertEquals(100_000_000L, UpstreamHttpClient.MAX_BODY_BYTES);
  }

  @Test
  void skipsHttp2PseudoHeadersAndBlacklistOnPassThrough() {
    // JDK HttpClient 默认走 HTTP/2，response.headers() 里带 :status。
    // 透传进 HTTP/1.1 响应即非法头名，httpx/OpenAI SDK 直接抛
    // RemoteProtocolError: illegal header line（curl 宽容所以此前未暴露）
    assertFalse(UpstreamHttpClient.forwardableResponseHeader(":status"));
    assertFalse(UpstreamHttpClient.forwardableResponseHeader(":authority"));
    assertFalse(UpstreamHttpClient.forwardableResponseHeader("Content-Length"));
    assertFalse(UpstreamHttpClient.forwardableResponseHeader("content-type"));
    assertFalse(UpstreamHttpClient.forwardableResponseHeader("Transfer-Encoding"));
    assertFalse(UpstreamHttpClient.forwardableResponseHeader("Bad Header"));
    assertTrue(UpstreamHttpClient.forwardableResponseHeader("x-request-id"));
    assertTrue(UpstreamHttpClient.forwardableResponseHeader("x-ratelimit-remaining-tokens"));
    assertTrue(UpstreamHttpClient.forwardableResponseHeader("grpc-encoding"));
    assertTrue(UpstreamHttpClient.forwardableResponseHeader("req-cost-time"));
  }

  @Test
  void parsesResponsesInputStringAndArray() {
    List<ConversationStore.ChatMessage> str = OpenAIResponseHttpHandler.parseMessages(
        JSON.parseObject("{\"input\":\"直接输入\"}"));
    assertEquals(1, str.size());
    assertEquals("user", str.get(0).role());
    assertEquals("直接输入", str.get(0).content());

    List<ConversationStore.ChatMessage> arr = OpenAIResponseHttpHandler.parseMessages(JSON.parseObject(
        "{\"input\":[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"块1\"},{\"type\":\"text\",\"text\":\"块2\"}]},{\"type\":\"function_call_output\"}]}"));
    assertEquals(1, arr.size());
    assertEquals("块1\n块2", arr.get(0).content());
  }
}
