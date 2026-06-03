import { apiUrl, ensureValidAccessToken, refreshTokens } from "./client";

export interface EtlMessage {
    docUuid: string;
    userId: string;
    status: string;
    message: string;
}

export type MessageHandler = (msg: EtlMessage) => void;

// 模块级 AbortController：全局仅维持一条 SSE 连接，新连接前中止旧的避免重复订阅
let controller: AbortController | null = null;

export const connectSse = async (onMessage: MessageHandler) => {
    if (controller) {
        // 重连前先中止旧连接：避免多个 SSE 流并存导致重复消息
        controller.abort();
    }
    controller = new AbortController();

    try {
        const openStream = async (allowRetry = true) => {
            const token = await ensureValidAccessToken();
            const response = await fetch(apiUrl("/api/v1/sse/subscribe"), {
                headers: {
                    Authorization: `Bearer ${token}`,
                    Accept: "text/event-stream",
                },
                signal: controller?.signal,
            });

            if (response.status === 401 && allowRetry) {
                const refreshed = await refreshTokens();
                return fetch(apiUrl("/api/v1/sse/subscribe"), {
                    headers: {
                        Authorization: `Bearer ${refreshed.access_token}`,
                        Accept: "text/event-stream",
                    },
                    signal: controller?.signal,
                });
            }

            return response;
        };

        const response = await openStream();

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
            // 5 秒后重连：避免连接失败时立即重试导致请求风暴
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
