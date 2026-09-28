package aw.httphandler;

import io.javalin.http.Context;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 上游 HTTP 调用的共享管线：HttpClient 工厂、body 空闲读看门狗、请求/响应头透传、请求体上限。
 * timeout 配置见 server.connect_timeout_seconds / request_timeout_seconds / read_idle_timeout_seconds。
 */
public final class UpstreamHttpClient {

  /** 请求体上限（50MB），超出返回 413 */
  static final long MAX_BODY_BYTES = 50L * 1024 * 1024;

  /** 透传给客户端时要排除的响应头：hop-by-hop 头 + 由代理/容器自管的头 */
  private static final Set<String> SKIP_RESPONSE_HEADERS = Set.of(
      "connection", "keep-alive", "proxy-authenticate", "proxy-authorization", "te", "trailer",
      "transfer-encoding", "upgrade", "content-length", "content-type", "cache-control", "server", "date");

  /** 透传给上游的客户端请求头白名单（鉴权类头除外，避免覆盖上游鉴权） */
  private static final Set<String> FORWARD_REQUEST_HEADERS = Set.of(
      "anthropic-version", "anthropic-beta", "x-request-id");

  /** 看门狗调度线程（守护线程，不阻止 JVM 退出） */
  private static final ScheduledExecutorService WATCHDOG =
      Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "upstream-read-watchdog");
        t.setDaemon(true);
        return t;
      });

  private UpstreamHttpClient() {}

  /** 按 server.connect_timeout_seconds 创建上游 HttpClient（Server 启动时建一次，注入全部 handler） */
  public static HttpClient create(Duration connectTimeout) {
    return HttpClient.newBuilder()
        .connectTimeout(connectTimeout)
        .build();
  }

  /**
   * 包装上游 body 流实现空闲读超时：每次读到数据重置计时，idleTimeout 内无新数据则由看门狗线程
   * close 底层流（解除阻塞读，抛出的 IOException 走 handler 现有异常/审计路径）。
   * 读取有进展即续命，长流式只要持续吐数据就不会被误杀；
   * 注意：客户端消费极慢（单块写回超过 idle 超时）同样会被掐断——上游静默与下游消费超时共用此防线。
   */
  static InputStream withReadWatchdog(InputStream upstream, Duration idleTimeout) {
    return new InputStream() {
      private ScheduledFuture<?> killer;

      private void arm() {
        if (killer != null) {
          killer.cancel(false);
        }
        killer = WATCHDOG.schedule(() -> {
          try {
            upstream.close();
          } catch (IOException ignored) {
          }
        }, idleTimeout.toMillis(), TimeUnit.MILLISECONDS);
      }

      @Override
      public int read() throws IOException {
        arm();
        return upstream.read();
      }

      @Override
      public int read(byte[] b, int off, int len) throws IOException {
        arm();
        return upstream.read(b, off, len);
      }

      @Override
      public void close() throws IOException {
        if (killer != null) {
          killer.cancel(false);
          killer = null;
        }
        upstream.close();
      }
    };
  }

  /**
   * 把上游响应头透传给客户端（限流头 x-ratelimit-*、请求 ID 等客户端 SDK 依赖）。
   * hop-by-hop 头及由代理自管的头排除在外；多值同名头用 addHeader 逐份追加（set 语义会让第二份覆盖第一份）。
   * Content-Type / Cache-Control 在调用后设置（二者均在排除名单内，不会被透传值干扰）。
   */
  static void passThroughHeaders(HttpResponse<InputStream> response, Context ctx) {
    response.headers().map().forEach((name, values) -> {
      if (!SKIP_RESPONSE_HEADERS.contains(name.toLowerCase(java.util.Locale.ROOT))) {
        for (String value : values) {
          ctx.res().addHeader(name, value);
        }
      }
    });
  }

  /** 把客户端请求头按白名单透传给上游（anthropic-version / anthropic-beta / x-request-id） */
  static void forwardClientHeaders(Context ctx, HttpRequest.Builder builder) {
    for (String name : FORWARD_REQUEST_HEADERS) {
      String value = ctx.header(name);
      if (value != null && !value.isBlank()) {
        builder.header(name, value);
      }
    }
  }
}
