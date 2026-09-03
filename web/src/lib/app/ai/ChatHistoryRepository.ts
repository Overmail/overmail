import type {PromptSegment} from "$lib/app/ai/prompt";
import {SvelteMap} from "svelte/reactivity";

export class ChatHistoryRepository {
    chats = new SvelteMap<string, AiChat>()
    private streamingHandler = new ChatResponseStreamingHandler();

    async loadChat(chatId: string, withActiveStream: boolean, forceReload: boolean = false) {
        if (!forceReload && this.chats.has(chatId)) {
            return;
        }
        const response = await fetch(`/api/webapp/ai/chat/${chatId}/history`);
        if (!response.ok) {
            console.error(`Failed to load chat history for chat ${chatId}: ${response.status} ${response.statusText}`);
            return;
        }

        const data = await response.json();

        const chat: AiChat = {
            id: data.chat_id,
            // The map returns nothing for a message type this client does not know, so unknown
            // types are dropped rather than left in the list as undefined.
            messages: data.messages.flatMap((message: any) => {
                const baseMessage = {
                    id: message.id,
                    created_at: new Date(message.created_at * 1000),
                };

                if (message.type === "user") {
                    return {
                        ...baseMessage,
                        type: "user",
                        content: message.content.map((segment: any) => {
                            switch (segment.type) {
                                case "text":
                                    return {
                                        type: "text",
                                        content: segment.content,
                                    };
                                case "email":
                                    return {
                                        type: "email",
                                        email: {id: segment.id},
                                    };
                                case "label":
                                    return {
                                        type: "label",
                                        label: {id: segment.id},
                                    };
                                case "sender":
                                    return {
                                        type: "sender",
                                        sender: {id: segment.id},
                                    };
                            }
                        }),
                    };
                } else if (message.type === "assistant") {
                    if (withActiveStream && message.pending) {
                        this.streamingHandler.subscribeTo({
                            chatId: chatId,
                            messageId: message.id,
                            onSnapshot: (content) => this.updateAssistantMessage(
                                chatId,
                                message.id,
                                (assistant) => ({...assistant, content: content}),
                            ),
                            onNewContent: (content) => this.updateAssistantMessage(
                                chatId,
                                message.id,
                                (assistant) => ({...assistant, content: assistant.content + content}),
                            ),
                            onComplete: () => this.updateAssistantMessage(
                                chatId,
                                message.id,
                                (assistant) => ({...assistant, pending: false}),
                            ),
                        })
                    }
                    return {
                        ...baseMessage,
                        type: "assistant",
                        pending: message.pending,
                        content: message.content,
                    };
                }
                return [];
            }),
        }


        this.chats.set(chatId, chat);
    }

    /**
     * Replaces one assistant message in a chat. Streaming only ever touches assistant messages,
     * so a user message under that id is as much a miss as an unknown id.
     */
    private updateAssistantMessage(
        chatId: string,
        messageId: string,
        update: (message: AssistantMessage) => AssistantMessage,
    ) {
        const chat = this.chats.get(chatId);
        if (!chat) return;
        const index = chat.messages.findIndex((message) => message.id === messageId);
        if (index === -1) return;
        const message = chat.messages[index];
        if (message.type !== "assistant") return;

        const messages = [...chat.messages];
        messages[index] = update(message);
        this.chats.set(chatId, {...chat, messages});
    }
}

class ChatResponseStreamingHandler {

    private sources: Map<string, EventSource> = new Map();

    subscribeTo(config: {
        chatId: string,
        messageId: string,
        /** The whole answer so far; replaces what is rendered instead of being appended to it. */
        onSnapshot: (content: string) => void,
        onNewContent: (content: string) => void,
        onComplete: () => void,
    }) {
        const existingSource = this.sources.get(config.messageId);
        if (existingSource) {
            if (existingSource.readyState === EventSource.OPEN) existingSource.close();
            this.sources.delete(config.messageId);
        }
        const eventSource = new EventSource(`/api/webapp/ai/chat/${config.chatId}/message/${config.messageId}/stream`);
        eventSource.onmessage = (event) => {
            const data = JSON.parse(event.data);
            if (data.type === "done") {
                config.onComplete();
                eventSource.close();
                this.sources.delete(config.messageId);
            } else if (data.type === "content") {
                config.onNewContent(data.content);
            } else if (data.type === "snapshot") {
                // Every stream opens with one, and a reconnect gets a fresh one: it is what makes
                // the retry below safe, no matter how much was missed while the stream was down.
                config.onSnapshot(data.content);
            }
        };
        eventSource.onerror = (error) => {
            console.error("Error in chat response streaming:", error);
            eventSource.close();

            this.sources.delete(config.messageId);

            setTimeout(() => {
                this.subscribeTo(config);
            }, 1000);
        };
    }
}

export type AiChat = {
    id: string,
    messages: AiChatMessage[],
}

export type AiChatMessage = {
    id: string,
    created_at: Date,
} & (
    {
        type: "user",
        content: PromptSegment[]
    } | {
        type: "assistant",
        pending: boolean,
        content: string
    }
)

type AssistantMessage = Extract<AiChatMessage, {type: "assistant"}>;
