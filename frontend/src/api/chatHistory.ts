import { apiUrl, authFetch } from "./client";

export interface ChatSessionItem {
  conversationId: string;
  title: string;
  titleStatus: string;
  lastMessageAt: string;
  createDate: string;
}

export interface ChatSessionPage {
  items: ChatSessionItem[];
  total: number;
  page: number;
  size: number;
}

export interface ChatHistoryMessage {
  id: string;
  role: "user" | "assistant" | string;
  content: string;
  modelId?: string;
  mode?: "rag" | "agent" | string;
  messageIndex: number;
  createDate: string;
}

const parseEnvelope = async <T>(response: Response): Promise<T> => {
  const json = await response.json();
  if (!response.ok || json.code !== 0) {
    throw new Error(json.msg || `Request failed: ${response.status}`);
  }
  return json.data as T;
};

export const listChatSessions = async (keyword = "", page = 1, size = 20): Promise<ChatSessionPage> => {
  const params = new URLSearchParams({
    page: String(page),
    size: String(size),
  });
  if (keyword.trim()) {
    params.set("keyword", keyword.trim());
  }
  const response = await authFetch(apiUrl(`/api/v1/chat/sessions?${params.toString()}`));
  return parseEnvelope<ChatSessionPage>(response);
};

export const listChatMessages = async (conversationId: string): Promise<ChatHistoryMessage[]> => {
  const response = await authFetch(apiUrl(`/api/v1/chat/sessions/${encodeURIComponent(conversationId)}/messages`));
  return parseEnvelope<ChatHistoryMessage[]>(response);
};

export const renameChatSession = async (conversationId: string, title: string): Promise<void> => {
  const response = await authFetch(apiUrl(`/api/v1/chat/sessions/${encodeURIComponent(conversationId)}`), {
    method: "PATCH",
    headers: {
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ title }),
  });
  await parseEnvelope<void>(response);
};

export const deleteChatSession = async (conversationId: string): Promise<void> => {
  const response = await authFetch(apiUrl(`/api/v1/chat/sessions/${encodeURIComponent(conversationId)}`), {
    method: "DELETE",
  });
  await parseEnvelope<void>(response);
};
