export const streamSsePost = async (
  url: string,
  body: Record<string, unknown> | null,
  onChunk: (event: string, data: string) => void,
  authHeader: string
) => {
  const response = await fetch(url, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "Accept": "text/event-stream",
      "Authorization": authHeader
    },
    body: body ? JSON.stringify(body) : "{}"
  });

  if (!response.ok || !response.body) {
    throw new Error(`Request failed: ${response.status}`);
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder("utf-8");
  let buffer = "";

  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
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
