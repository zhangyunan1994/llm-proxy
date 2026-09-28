package aw.httphandler;

import java.net.http.HttpClient;
import java.time.Duration;

/** 全部 handler 共享的上游 HttpClient（连接超时 30s），避免各 handler 独立建 client 浪费连接资源 */
final class UpstreamHttpClient {

  static final HttpClient SHARED = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(30))
      .build();

  private UpstreamHttpClient() {}
}
