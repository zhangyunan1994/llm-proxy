-- 对话记录表结构（SQLite）
-- 应用侧每次建连接后建议执行: PRAGMA foreign_keys = ON

-- 每次代理转发请求记录一条（api 区分来源接口）
CREATE TABLE IF NOT EXISTS conversations (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    api               TEXT,                        -- 请求来源接口: openai.chat.completions / openai.responses / anthropic.messages / embeddings / rerank
    session_id        TEXT,                        -- 客户端会话标识（如 X-Session-Id 请求头），可空
    client_api_key    TEXT,                        -- 客户端 Bearer key（启用鉴权时记录），可空
    model             TEXT NOT NULL,               -- 请求的模型名
    provider          TEXT,                        -- 路由到的上游 provider 名
    stream            INTEGER NOT NULL DEFAULT 0,  -- 是否流式请求: 0/1
    status_code       INTEGER,                     -- 上游响应状态码
    request_body      TEXT,                        -- 完整请求 JSON
    response_body     TEXT,                        -- 完整响应 JSON（流式时为 SSE 拼接结果）
    prompt_tokens     INTEGER,                     -- usage.prompt_tokens / input_tokens
    completion_tokens INTEGER,                     -- usage.completion_tokens / output_tokens
    total_tokens      INTEGER,                     -- usage.total_tokens
    cache_tokens      INTEGER,                     -- 命中缓存 token 数（OpenAI cached_tokens / Anthropic cache_read_input_tokens）
    latency_ms        INTEGER,                     -- 上游耗时（毫秒）
    error_message     TEXT,                        -- 失败原因
    created_at        TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
);

-- 请求中 messages 数组逐条展开保存
CREATE TABLE IF NOT EXISTS messages (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    conversation_id INTEGER NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    seq             INTEGER NOT NULL,              -- 在 messages 数组中的顺序，从 0 开始
    role            TEXT NOT NULL,                 -- system / user / assistant / tool
    content         TEXT,                          -- 消息内容（多模态数组时存 JSON 字符串）
    created_at      TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
);

CREATE INDEX IF NOT EXISTS idx_conversations_created_at ON conversations(created_at);
CREATE INDEX IF NOT EXISTS idx_conversations_model      ON conversations(model);
CREATE INDEX IF NOT EXISTS idx_messages_conversation    ON messages(conversation_id, seq);
