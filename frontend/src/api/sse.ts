import { ensureValidAccessToken, refreshTokens } from "./client";

export const streamSsePost = async (
  url: string,
  body: Record<string, unknown> | null,
  onChunk: (event: string, data: string) => void,
  signal?: AbortSignal
) => {
  const requestInit = {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "Accept": "text/event-stream",
    },
    body: body ? JSON.stringify(body) : "{}",
    signal
  } satisfies RequestInit;

  const openStream = async (allowRetry = true) => {
    const token = await ensureValidAccessToken();
    const response = await fetch(url, {
      ...requestInit,
      headers: {
        ...requestInit.headers,
        "Authorization": `Bearer ${token}`
      }
    });

    if (response.status === 401 && allowRetry) {
      const refreshed = await refreshTokens();
      return fetch(url, {
        ...requestInit,
        headers: {
          ...requestInit.headers,
          "Authorization": `Bearer ${refreshed.access_token}`
        }
      });
    }

    return response;
  };

  const response = await openStream();

  if (!response.ok || !response.body) {
    throw new Error(`Request failed: ${response.status}`);
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder("utf-8");
  let buffer = "";

  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    // stream: true：多字节 UTF-8 字符可能被分割到不同 chunk，流模式让 TextDecoder 缓存不完整字节
    buffer += decoder.decode(value, { stream: true });

    // Split by double newline (SSE block separator)
    const blocks = buffer.split("\n\n");
    buffer = blocks.pop() || "";

    for (const block of blocks) {
      const lines = block.split("\n");
      let eventType = "message";
      let dataBuffer = "";

      for (const line of lines) {
        if (line.startsWith("event:")) {
          eventType = line.replace(/^event:\s?/, "").trim();
        } else if (line.startsWith("data:")) {
          // SSE 规范允许 data 字段跨多行：逐行累积后用 trim() 去除末尾换行
          dataBuffer += line.replace(/^data:\s?/, "") + "\n";
        }
      }

      if (dataBuffer) {
        // Remove trailing newline added by data accumulation
        onChunk(eventType, dataBuffer.trim());
      }
    }
  }
};
