import type {Prompt} from "$lib/app/ai/prompt";
import {ChatHistoryRepository, type AiChatMessage} from "$lib/app/ai/ChatHistoryRepository";

export class AiChatViewModel {
    chats: AiChat[] = $state([]);

    currentChatId: string | null = $state(null);
    currentChat: AiChat | null = $derived(this.chats.find((chat) => chat.id === this.currentChatId) ?? null);

    private chatHistoryRepository = new ChatHistoryRepository();

    /** Messages of the open chat, oldest first. Empty while none is selected or loaded. */
    currentChatMessages: AiChatMessage[] = $derived(
        this.currentChatId === null
            ? []
            : this.chatHistoryRepository.chats.get(this.currentChatId)?.messages ?? []
    );

    /** Newest first, regardless of whether a chat arrived with a page or as a live update. */
    chatsNewestFirst: AiChat[] = $derived(
        [...this.chats].sort((a, b) => b.created_at.getTime() - a.created_at.getTime())
    );

    /**
     * Creation time of the user's oldest chat overall, reported by the server with every page.
     * Null until the first page arrived, or when the user has no chats at all.
     */
    oldestCreatedAt: Date | null = $state(null);

    /** The prompt is on its way to the server; the answer message does not exist yet. */
    private isSending: boolean = $state(false);

    /**
     * An answer is being written -- from the moment the prompt is sent until the stream of the
     * agent message ends. The prompt stays blocked for as long.
     */
    isAnswering: boolean = $derived(
        this.isSending
        || this.currentChatMessages.some((message) => message.type === "assistant" && message.pending)
    );

    /** A page is on its way; the scroll handler must not ask for the same one again. */
    isLoadingChats: boolean = $state(true);

    /**
     * Whether paging further down can still turn anything up. An empty page would say the same,
     * but only after a pointless round trip -- and infinite scroll would keep asking.
     */
    hasMoreChats: boolean = $derived.by(() => {
        if (this.oldestCreatedAt === null) return false;
        const oldestLoaded = this.chatsNewestFirst.at(-1);
        if (oldestLoaded === undefined) return true;
        return oldestLoaded.created_at.getTime() > this.oldestCreatedAt.getTime();
    });

    private webSocketHandler: AiChatWebSocketHandler;

    constructor() {
        this.webSocketHandler = new AiChatWebSocketHandler({
            onChats: (chats, oldestCreatedAt) => {
                // A page can overlap with what is already here: the server's cursor includes the
                // creation timestamp of the oldest chat it sent, so a chat sharing that timestamp
                // is not lost between pages.
                chats.forEach((chat) => this.upsertChat(chat));
                this.oldestCreatedAt = oldestCreatedAt;
                this.isLoadingChats = false;
            },
            onChatUpserted: (chat) => this.upsertChat(chat),
            onChatDeleted: (chatId) => {
                this.chats = this.chats.filter((chat) => chat.id !== chatId);
                if (this.currentChatId === chatId) this.currentChatId = null;
            },
        });

        this.webSocketHandler.start();
    }

    dispose() {
        this.webSocketHandler.stop();
    }

    /** The next page below the oldest chat loaded so far; the server tracks the cursor. */
    loadMoreChats() {
        if (this.isLoadingChats || !this.hasMoreChats) return;

        this.isLoadingChats = true;
        this.webSocketHandler.requestMoreChats();
    }

    private upsertChat(chat: AiChat) {
        const index = this.chats.findIndex((existing) => existing.id === chat.id);
        if (index === -1) {
            this.chats.push(chat);
        } else {
            this.chats[index] = chat;
        }
    }

    async onChatHovered(chatId: string) {
        await this.chatHistoryRepository.loadChat(chatId, false, false);
    }

    async onChatSelected(chatId: string) {
        this.currentChatId = chatId;
        await this.chatHistoryRepository.loadChat(chatId, true, true);
    }

    /**
     * Asks the same question again. The old answer is replaced rather than kept: the server
     * empties the message and queues it, and the reload picks it up as pending again.
     */
    async retryMessage(messageId: string) {
        const chatId = this.currentChatId;
        if (chatId === null || this.isAnswering) return;

        this.isSending = true;
        try {
            const response = await fetch(
                `/api/webapp/ai/chat/${chatId}/message/${messageId}/retry`,
                {method: "POST"},
            );
            if (!response.ok) {
                console.error(`Failed to retry message ${messageId}: ${response.status} ${response.statusText}`);
                return;
            }

            await this.chatHistoryRepository.loadChat(chatId, true, true);
        } finally {
            this.isSending = false;
        }
    }

    async onPromptSubmitted(
        prompt: Prompt
    ) {
        this.isSending = true;
        try {
            const response = await fetch('/api/webapp/ai/chat', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                },
                body: JSON.stringify({
                    chat_id: this.currentChatId,
                    prompt: {
                        type: prompt.type,
                        segments: prompt.segments.map((segment) => {
                            switch (segment.type) {
                                case "text":
                                    return {
                                        type: "text",
                                        content: segment.content,
                                    };
                                case "email":
                                    return {
                                        type: "email",
                                        id: segment.email.id,
                                    };
                                case "label":
                                    return {
                                        type: "label",
                                        id: segment.label.id,
                                    };
                                case "sender":
                                    return {
                                        type: "sender",
                                        id: segment.sender.id,
                                    };
                            }
                        })
                    }
                }),
            });

            const data = await response.json();
            this.currentChatId = data.chat_id;

            await this.chatHistoryRepository.loadChat(data.chat_id, true, true);
        } finally {
            // Released once the history holds the pending answer, which is what carries the
            // waiting state from here on.
            this.isSending = false;
        }
    }
}

class AiChatWebSocketHandler {
    private webSocket: WebSocket | null = null;
    private isStopped: boolean = false;

    private readonly onChats: (chats: AiChat[], oldestCreatedAt: Date | null) => void;
    private readonly onChatUpserted: (chat: AiChat) => void;
    private readonly onChatDeleted: (chatId: string) => void;

    constructor(config: {
        onChats: (chats: AiChat[], oldestCreatedAt: Date | null) => void,
        onChatUpserted: (chat: AiChat) => void,
        onChatDeleted: (chatId: string) => void,
    }) {
        this.onChats = config.onChats;
        this.onChatUpserted = config.onChatUpserted;
        this.onChatDeleted = config.onChatDeleted;
    }

    private requestQueue: AiWebsocketClientMessage[] = [];

    start() {
        this.webSocket = new WebSocket('/api/webapp/ai/socket');
        this.webSocket.onclose = (e) => {
            if (!e.wasClean && !this.isStopped) setTimeout(() => this.start(), 1000);
        }

        this.webSocket.onopen = () => {
            const q = [...this.requestQueue];
            this.requestQueue = [];
            q.forEach((message) => this.request(message));
        }

        this.webSocket.onmessage = (event) => {
            const message: AiWebsocketServerMessage = JSON.parse(event.data);

            switch (message.type) {
                case "data.chats":
                    this.onChats(
                        message.chats.map((chat) => toAiChat(chat)),
                        message.oldest_created_at === null
                            ? null
                            : new Date(message.oldest_created_at * 1000),
                    );
                    break;
                case "update.chat.upsert":
                    this.onChatUpserted(toAiChat(message.chat));
                    break;
                case "update.chat.delete":
                    this.onChatDeleted(message.chat_id);
                    break;
            }
        }
    }

    private request(message: AiWebsocketClientMessage) {
        if (this.webSocket?.readyState === WebSocket.OPEN) {
            this.webSocket.send(JSON.stringify(message));
        } else {
            this.requestQueue.push(message);
        }
    }

    requestMoreChats() {
        this.request({type: "request.chats.more"});
    }

    stop() {
        this.isStopped = true;
        this.webSocket?.close();
        this.webSocket = null;
    }
}

function toAiChat(chat: AiChatPayload): AiChat {
    return {
        id: chat.id,
        name: chat.name,
        name_set_by_user: chat.name_set_by_user,
        created_at: new Date(chat.created_at * 1000),
    };
}

type AiChatPayload = {
    id: string,
    name: string | null,
    name_set_by_user: boolean,
    created_at: number,
}

type AiWebsocketServerMessage = {
    // A page of chats, newest first. The first one arrives on connect, the rest on request.
    type: "data.chats",
    chats: AiChatPayload[],
    /** Creation time of the oldest chat the user has, null when there are none. */
    oldest_created_at: number | null,
} | {
    type: "update.chat.upsert",
    chat: AiChatPayload,
} | {
    type: "update.chat.delete",
    chat_id: string,
}

type AiWebsocketClientMessage = {
    type: "request.chats.more",
}

export type AiChat = {
    id: string,
    /** Null until the chat has a name; it is written after the first message. */
    name: string | null,
    name_set_by_user: boolean,
    created_at: Date,
}
