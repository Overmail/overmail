import {EmailBodyRepository} from "$lib/repository/EmailBodyRepository";

export class EmailStackViewModel {
    emails: StackEmail[] = $state([])
    currentEmailId = $state<string | null>(null);
    remainingEmails = $derived.by(() => {
        if (!this.currentEmailId) return this.emails;
        const currentEmailIndex = this.emails.findIndex(e => e.id === this.currentEmailId);
        if (currentEmailIndex === -1) return this.emails;
        return this.emails.slice(currentEmailIndex + 1);
    })

    private webSocketHandler: EmailStackWebSocketHandler;

    constructor() {
        this.webSocketHandler = new EmailStackWebSocketHandler({
            onEmails: (emails) => {
                emails.forEach(email => {
                    if (!this.emails.find(e => e.id === email.id)) {
                        this.emails.push(email);
                    } else {
                        const index = this.emails.findIndex(e => e.id === email.id);
                        this.emails[index] = email;
                    }
                })
            }
        })

        this.webSocketHandler.start();
    }

    dispose() {
        this.webSocketHandler.stop();
    }
}

class EmailStackWebSocketHandler {
    private webSocket: WebSocket | null = null;
    private isStopped: boolean = false;

    private readonly emailBodyRepository = new EmailBodyRepository();
    private readonly onEmails: (emails: StackEmail[]) => void

    constructor(config: {onEmails: (emails: StackEmail[]) => void}) {
        this.onEmails = config.onEmails;
    }

    start() {
        this.webSocket = new WebSocket('/api/stack');
        this.webSocket.onclose = (e) => {
            if (!e.wasClean && !this.isStopped) setTimeout(() => this.start(), 1000);
        }

        this.webSocket.onmessage = async (event) => {
            const message: StackWebsocketServerMessage = JSON.parse(event.data);

            switch (message.type) {
                case "data.emails":
                    const emails: StackEmail[] = await Promise.all(
                        message.emails.map(async (email) => ({
                            ...email,
                            body: await this.emailBodyRepository.getBody(email.id),
                            sent_at: new Date(email.sent_at * 1000),
                        }))
                    );
                    this.onEmails(emails);
                    break;
            }
        }
    }

    stop() {
        this.isStopped = true;
        this.webSocket?.close();
        this.webSocket = null;
    }
}

type StackWebsocketServerMessage = {
    type: "data.emails",
    emails: {
        id: string,
        subject: string,
        from: EmailUser,
        to: EmailUser[],
        cc: EmailUser[],
        bcc: EmailUser[],
        sent_at: number,
    }[]
}

export type StackEmail = {
    id: string,
    subject: string,
    from: EmailUser,
    to: EmailUser[],
    cc: EmailUser[],
    bcc: EmailUser[],
    sent_at: Date,
    body: {
        text: string | null,
        html: string | null,
    }
}

export type EmailUser = {
    name: string,
    email: string,
}