import { getAuthHeader, apiUrl } from "./client";

export interface EtlMessage {
    docUuid: string;
    userId: string;
    status: string;
    message: string;
}

export type MessageHandler = (msg: EtlMessage) => void;

let controller: AbortController | null = null;

export const connectSse = async (onMessage: MessageHandler) => {
    if (controller) {
        controller.abort();
    }
    controller = new AbortController();

    try {
        const response = await fetch(apiUrl("/api/v1/sse/subscribe"), {
            headers: {
                Authorization: getAuthHeader(),
                Accept: "text/event-stream",
            },
            signal: controller.signal,
        });

        if (!response.ok) {
            console.error("SSE connection failed:", response.status);
            return;
        }

        const reader = response.body?.getReader();
        if (!reader) return;

        const decoder = new TextDecoder();
        let buffer = "";

        while (true) {
            const { done, value } = await reader.read();
            if (done) break;

            const chunk = decoder.decode(value, { stream: true });
            buffer += chunk;

            const lines = buffer.split("\n\n");
            // The last part might be incomplete, keep it in buffer
            buffer = lines.pop() || "";

            for (const line of lines) {
                // Parse standard SSE format:
                // id: ...
                // event: ...
                // data: ...

                // We are primarily interested in "data: {...}"
                // But typically lines inside a block are split by \n
                const innerLines = line.split("\n");
                let dataStr = "";

                for (const inner of innerLines) {
                    if (inner.startsWith("data:")) {
                        dataStr = inner.substring(5).trim();
                    }
                }

                if (dataStr) {
                    try {
                        const msg = JSON.parse(dataStr) as EtlMessage;
                        onMessage(msg);
                    } catch (e) {
                        // ignore parse error or keepalive
                    }
                }
            }
        }
    } catch (e: any) {
        if (e.name !== "AbortError") {
            console.error("SSE stream error", e);
            // Reconnect logic could go here (with backoff)
            setTimeout(() => connectSse(onMessage), 5000);
        }
    }
};

export const disconnectSse = () => {
    if (controller) {
        controller.abort();
        controller = null;
    }
};
