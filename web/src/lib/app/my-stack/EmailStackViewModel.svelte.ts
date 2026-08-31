import {EmailBodyRepository} from "$lib/repository/EmailBodyRepository";

const MAX_EMAILS_BEFORE_REFETCH = 5;

export class EmailStackViewModel {
    emails: StackEmail[] = $state([])
    currentEmailId = $state<string | null>(null);
    currentEmail = $derived(this.emails.find(e => e.id === this.currentEmailId));
    currentEmailIndex = $derived(this.emails.findIndex(e => e.id === this.currentEmailId));

    private webSocketHandler: EmailStackWebSocketHandler;

    constructor() {
        this.webSocketHandler = new EmailStackWebSocketHandler({
            onEmails: (emails) => {
                const isFirstBatchOfEmails = this.emails.length === 0;
                emails.forEach(email => {
                    if (!this.emails.find(e => e.id === email.id)) {
                        this.emails.push(email);
                    } else {
                        const index = this.emails.findIndex(e => e.id === email.id);
                        this.emails[index] = email;
                    }
                })

                if (isFirstBatchOfEmails && emails.length > 0) {
                    this.currentEmailId = emails[0].id;
                }
            },
            onLabelsUpserted: (emailId, labels) => {
                console.log(labels);
                const email = this.emails.find(e => e.id === emailId);
                if (!email) return;
                labels.forEach(label => {
                    const index = email.labels.findIndex(l => l.id === label.id);
                    if (index === -1) {
                        email.labels.push(label);
                    } else {
                        email.labels[index] = label;
                    }
                });
            },
            onLabelsDeleted: (emailId, labelIds) => {
                const email = this.emails.find(e => e.id === emailId);
                if (!email) return;
                email.labels = email.labels.filter(l => !labelIds.includes(l.id));
            },
            onAvatarResolved: (address, avatarUrl) => {
                // By address, not by mail: one sender fills a whole stack, and the picture found
                // for it is the same in every mail it appears in.
                this.emails.forEach(email => {
                    [email.from, ...email.to, ...email.cc, ...email.bcc]
                        .filter(participant => participant.email === address)
                        .forEach(participant => participant.avatar_url = avatarUrl);
                });
            },
        })

        this.webSocketHandler.start();
    }

    dispose() {
        this.webSocketHandler.stop();
    }

    onKeepEmail() {
        if (!this.currentEmailId) return;
        this.currentEmail!!.classification = {type: "keep"};

        this.onNextEmail();
    }

    onArchiveOrUnarchiveEmail() {
        if (!this.currentEmailId) return;
        if (this.currentEmail!!.classification?.type === "archive") {
            this.currentEmail!!.classification = null;
            this.webSocketHandler.unarchiveEmail(this.currentEmailId);
        } else {
            this.currentEmail!!.classification = {type: "archive"};
            this.webSocketHandler.archiveEmail(this.currentEmailId);
        }

        this.onNextEmail();
    }

    onNextEmail() {
        if (this.currentEmailIndex === -1) return;
        if (this.currentEmailIndex + 1 >= this.emails.length) return;
        this.currentEmailId = this.emails[this.currentEmailIndex + 1].id;

        const remainingEmails = this.emails.length - (this.currentEmailIndex + 1);
        if (remainingEmails <= MAX_EMAILS_BEFORE_REFETCH) {
            this.webSocketHandler.requestNextBatch();
        }
    }

    onPreviousEmail() {
        if (this.currentEmailIndex === -1) return;
        if (this.currentEmailIndex - 1 < 0) return;
        this.currentEmailId = this.emails[this.currentEmailIndex - 1].id;
    }

    async onRequestEmailClassification(emailId: string) {
        const result = await fetch(`/api/emails/${emailId}/classify`, {
            method: "POST",
        });
        return result.ok;
    }
}

class EmailStackWebSocketHandler {
    private webSocket: WebSocket | null = null;
    private isStopped: boolean = false;

    private readonly emailBodyRepository = new EmailBodyRepository();
    private readonly onEmails: (emails: StackEmail[]) => void
    private readonly onLabelsUpserted: (emailId: string, labels: Label[]) => void
    private readonly onLabelsDeleted: (emailId: string, labelIds: string[]) => void
    private readonly onAvatarResolved: (address: string, avatarUrl: string) => void

    constructor(config: {
        onEmails: (emails: StackEmail[]) => void,
        onLabelsUpserted: (emailId: string, labels: Label[]) => void,
        onLabelsDeleted: (emailId: string, labelIds: string[]) => void,
        onAvatarResolved: (address: string, avatarUrl: string) => void,
    }) {
        this.onEmails = config.onEmails;
        this.onLabelsUpserted = config.onLabelsUpserted;
        this.onLabelsDeleted = config.onLabelsDeleted;
        this.onAvatarResolved = config.onAvatarResolved;
    }

    private requestQueue: StackWebsocketClientMessage[] = [];

    start() {
        this.webSocket = new WebSocket('/api/stack');
        this.webSocket.onclose = (e) => {
            if (!e.wasClean && !this.isStopped) setTimeout(() => this.start(), 1000);
        }

        this.webSocket.onopen = () => {
            const q = [...this.requestQueue];
            this.requestQueue = [];
            q.forEach((message) => this.request(message));
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
                            classification: null,
                        }))
                    );
                    this.onEmails(emails);
                    break;
                case "update.email.tags.upsert":
                    this.onLabelsUpserted(message.email_id, message.tags);
                    break;
                case "update.email.tags.delete":
                    this.onLabelsDeleted(message.email_id, message.tag_ids);
                    break;
                case "update.avatar":
                    this.onAvatarResolved(message.address, message.avatar_url);
                    break;
            }
        }
    }

    private request(message: StackWebsocketClientMessage) {
        if (this.webSocket?.readyState === WebSocket.OPEN) {
            this.webSocket.send(JSON.stringify(message));
        } else {
            this.requestQueue.push(message);
        }
    }

    requestNextBatch() {
        this.request({type: "request.emails"});
    }

    archiveEmail(emailId: string) {
        this.request({
            type: "update.email.archive",
            email_id: emailId
        });
    }

    unarchiveEmail(emailId: string) {
        this.request({
            type: "update.email.unarchive",
            email_id: emailId
        });
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
        labels: Label[]
    }[]
} | {
    type: "update.email.tags.upsert",
    email_id: string,
    tags: Label[],
} | {
    type: "update.email.tags.delete",
    email_id: string,
    tag_ids: string[],
} | {
    // A picture the server looked up after the batch went out; see AvatarQueue on the server.
    type: "update.avatar",
    address: string,
    avatar_url: string,
}

type StackWebsocketClientMessage = {
    type: "request.emails",
} | {
    type: "update.email.archive",
    email_id: string,
} | {
    type: "update.email.unarchive",
    email_id: string,
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
    },
    classification: Classification | null,
    labels: Label[],
}

export type Label = {
    id: string;
    name: string;
    color: string;
    assignment_reason: string | null;
    label_description: string | null;
}

export type Classification = {
    type: "keep"
} | {
    type: "archive"
}

export type EmailUser = {
    name: string,
    email: string,
    /** Null while no picture has been found for the address -- the card shows initials then. */
    avatar_url: string | null,
}