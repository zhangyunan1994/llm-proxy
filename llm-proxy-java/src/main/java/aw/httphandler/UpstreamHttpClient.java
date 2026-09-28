package aw.httphandler;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 全部 handler 共享的上游 HttpClient（连接超时 30s），避免各 handler 独立建 client 浪费连接资源。
 * 另提供 body 读取看门狗：HttpRequest.timeout() 只覆盖到响应头到达，body 传输阶段（含 SSE 流）
 * 的阻塞读不受它约束——上游半截挂死会永久卡死请求线程，由看门狗兜底。
 */
final class UpstreamHttpClient {

  static final HttpClient SHARED = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(30))
      .build();

  /** 看门狗调度线程（守护线程，不阻止 JVM 退出） */
  private static final ScheduledExecutorService WATCHDOG =
      Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "upstream-read-watchdog");
        t.setDaemon(true);
        return t;
      });

  /**
   * 包装上游 body 流实现空闲读超时：每次读到数据重置计时，idleTimeout 内无新数据则由看门狗线程
   * close 底层流（解除阻塞读，抛出的 IOException 走 handler 现有异常/审计路径）。
   * 读取有进展即续命，长流式只要持续吐数据就不会被误杀。
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

  private UpstreamHttpClient() {}
}
