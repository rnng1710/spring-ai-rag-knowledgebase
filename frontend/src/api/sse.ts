export const streamSsePost = async (
  url: string,
  body: Record<string, unknown> | null,
  onChunk: (data: string) => void,
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
    const events = buffer.split("\n\n");
    buffer = events.pop() || "";
    for (const evt of events) {
      const lines = evt.split("\n");
      for (const line of lines) {
        if (line.startsWith("data:")) {
          onChunk(line.replace(/^data:\s?/, ""));
        }
      }
    }
  }
};
