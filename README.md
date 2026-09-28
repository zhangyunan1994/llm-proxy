# llm-proxy

Java 实现的 LLM API 网关。对外提供统一的 OpenAI / Anthropic 风格入口，按模型名路由到不同
上游 provider（官方 API 或中转网关均可），并将每次请求完整留痕到 SQLite，便于审计与用量统计。

## 特性

- **统一入口**：5 个转发端点（chat.completions / responses / messages / embeddings / rerank），
  客户端无需感知上游差异
- **客户端鉴权**：`client_api_keys` 非空时校验 `Authorization: Bearer`，恒定时间比较防时序侧信道
- **模型路由与名称映射**：对外模型名 → `provider + 上游真实模型名`，请求中的 `model` 字段自动替换
- **SSE 流式透传**：逐块读取上游、逐块 flush 下发，保证流式体验
- **会话审计**：SQLite 记录来源接口、token 用量（含缓存命中）、耗时、错误信息，记录失败不影响转发
- **配置 fail-fast 校验**：启动时一次性报出所有配置错误（重名、引用不存在等）
- **幂等建表 + 自动迁移**：启动自动建表；老库缺新列时自动 `ALTER TABLE` 补列，不丢数据

## 环境要求

- Java 25
- Maven 3.9+

主要依赖：[Javalin](https://javalin.io/) 7.2.3（Web 框架）、fastjson2（JSON）、SnakeYAML（配置）、
sqlite-jdbc（存储）、SLF4J（日志）。

## 快速开始

1. 在工作目录准备 `config.yaml`（格式见[配置说明](#配置说明)，该文件含密钥，已在 `.gitignore` 中排除）

2. 启动服务：

   ```bash
   # 方式一：Maven 直接运行（默认读取当前目录 config.yaml）
   mvn compile exec:java -Dexec.mainClass=aw.Server

   # 方式二：指定配置文件路径
   mvn compile exec:java -Dexec.mainClass=aw.Server -Dllm-proxy.config=/path/to/config.yaml
   ```

   配置文件查找顺序：`-Dllm-proxy.config` 系统属性 → 当前目录 `./config.yaml` → classpath `/config.yaml`。

3. 验证：

   ```bash
   curl http://localhost:<端口>/v1/models
   ```

## 配置说明

```yaml
server:
  addr: ":18080"                 # 监听端口，支持 "18080" / ":18080"（不支持绑定特定网卡），缺省 8080
  connect_timeout_seconds: 30    # 上游连接超时（秒，缺省 30）
  request_timeout_seconds: 300   # 上游响应头超时（秒，缺省 300；不含 body 传输）
  read_idle_timeout_seconds: 300 # 上游 body 空闲读超时（秒，缺省 300；流式分片间隔超过该值即断流）
  client_api_keys:               # 客户端鉴权 key 列表；非空时启用鉴权，为空/缺省则不鉴权（启动时告警）；空白条目视为配置错误
    - "sk-client-xxx"

providers:                       # 上游厂商列表，name 不可重复
  - name: xiaomi
    openai_base_url: "https://xxx/v1"       # OpenAI 风格端点根地址（必填，chat/responses/embeddings/rerank）
    anthropic_base_url: "https://xxx/v1"    # Anthropic 风格端点根地址（messages）；可选，缺省回退 openai_base_url
    api_key: "sk-xxx"                        # 转发时以 Authorization: Bearer 携带；为空则不发该头（适合本地免 key 网关）；直连内置厂商域名（openai.com、deepseek.com 等 27 家）时必填，否则启动报错
    supported_api_types: []                  # 路由校验：非空时校验 model.capabilities 与本列表的匹配；为空则不限制。取值：openai.chat.completions / openai.responses / anthropic.messages / embeddings / rerank
  - name: bailian
    openai_base_url: "https://xxx/compatible-mode/v1"
    api_key: "sk-yyy"

models:                          # 对外模型列表，name 不可重复，provider 必须已存在
  - name: mimo-v2.6-pro          # 对外暴露的模型名（客户端请求里填的名字）
    provider: xiaomi             # 路由到哪个 provider
    upstream: MiMo-v2.6-Pro      # 发给上游的真实模型名，缺省等于 name
    max_tokens: 32768            # 模型元数据，透出在 GET /v1/models（未配置为 null）
    context_length: 262144       # 上下文窗口元数据，透出在 GET /v1/models
    capabilities: ["chat"]       # 能力标签，透出在 GET /v1/models；provider 配置了 supported_api_types 时参与路由校验：rerank/embeddings 需对应 apitype，chat 需至少一种 chat 格式
```

## API 端点

| 端点 | 上游 | 落库 api 标识 |
|---|---|---|
| `POST /v1/chat/completions` | `{openai_base_url}/chat/completions` | `openai.chat.completions` |
| `POST /v1/responses` | `{openai_base_url}/responses` | `openai.responses` |
| `POST /v1/messages` | `{anthropic_base_url 或 openai_base_url}/messages` | `anthropic.messages` |
| `POST /v1/embeddings` | `{openai_base_url}/embeddings` | `embeddings` |
| `POST /v1/rerank` | `{openai_base_url}/rerank` | `rerank` |
| `GET /v1/models` | —（本地返回配置的模型列表） | 不落库 |

- 请求中未配置的 `model` 返回 `400 Invalid model`（model 名精确匹配，**区分大小写**）
- **鉴权**：`client_api_keys` 非空时，5 个转发端点要求 `Authorization: Bearer <key>`（key 须在列表中），
  缺失或不合法返回 `401 Unauthorized`；`/v1/models` 无需鉴权；CORS 预检（OPTIONS）放行
- 可选请求头 `X-Session-Id`：客户端会话标识，落入 `conversations.session_id`，用于关联同一会话的多次调用
- 请求体上限 50MB，超出返回 `413 Payload too large`
- **头透传**：上游响应头透传给客户端（限流头 `x-ratelimit-*`、请求 ID 等，hop-by-hop 头除外）；
  客户端的 `anthropic-version` / `anthropic-beta` / `x-request-id` 请求头透传给上游
- `usage` 解析尽力而为：流式响应从 SSE 分片中提取（chat.completions 流式由代理自动注入
  `stream_options.include_usage`；responses 在 `response.completed` 事件；anthropic.messages 取
  `message_start` + `message_delta`），字段缺失时对应列为 NULL
- **上游超时（三层）**：连接 `connect_timeout_seconds`（默认 30s）→ 响应头 `request_timeout_seconds`
  （默认 300s，不含 body 传输）→ body 空闲读 `read_idle_timeout_seconds`（默认 300s）。第三层由看门狗
  实现：上游停止吐数据（或客户端消费极慢）超过该值即断流，已收到的半截响应仍会落库审计

## 会话审计（SQLite）

数据写入工作目录下的 `sample.db`（WAL 模式），表结构见
[`llm-proxy-java/src/main/resources/schema.sql`](llm-proxy-java/src/main/resources/schema.sql)。

`conversations`（每次请求一条）：

| 字段 | 说明 |
|---|---|
| `api` | 来源接口标识，取值见上表 |
| `session_id` | `X-Session-Id` 请求头 |
| `client_api_key` | 客户端 Bearer key（启用鉴权时记录，用于区分调用方） |
| `model` | 客户端请求的模型名（对外名） |
| `provider` | 路由到的上游 provider 名 |
| `stream` / `status_code` | 是否流式；上游响应码 |
| `request_body` / `response_body` | 完整请求 / 响应（流式为 SSE 拼接结果） |
| `prompt_tokens` / `completion_tokens` / `total_tokens` | token 用量（Anthropic 的 `total_tokens` 由 input+output 计算） |
| `cache_tokens` | 命中缓存的 token 数：OpenAI 取 `cached_tokens`，Anthropic 取 `cache_read_input_tokens` |
| `latency_ms` / `error_message` | 上游耗时；失败原因 |

`messages`（请求 messages 数组逐条展开，多模态 content 存 JSON 字符串）。

落库失败仅记日志，不影响代理转发。升级新增列时启动自动补列（`ALTER TABLE ADD COLUMN`）。

## 项目结构

```
llm-proxy-java/
├── pom.xml                          # Java 25 + Maven
└── src/main/
    ├── java/aw/
    │   ├── Server.java              # 入口：装配 handler、注册路由
    │   ├── config/ConfigLoader.java # config.yaml 加载与 fail-fast 校验
    │   ├── db/ConversationStore.java# SQLite 审计存储（建表、迁移、写入）
    │   ├── httphandler/             # 各端点转发 handler（含 usage 解析）
    │   └── util/StringUtils.java
    └── resources/
        ├── schema.sql               # 建表语句
        └── simplelogger.properties  # SLF4J 日志配置
```

## 已知限制

- Anthropic 上游当前统一使用 `Authorization: Bearer` 头，适合中转网关；直连 Anthropic 官方
  API 需要改为 `x-api-key` + `anthropic-version` 头
- rerank 的 usage 各家格式不一（Cohere/SiliconFlow 在 `meta.billed_units`，Jina 在
  `usage.total_tokens`），按常见格式尽力提取
- 请求/响应体全量落库，大流量场景注意磁盘占用
