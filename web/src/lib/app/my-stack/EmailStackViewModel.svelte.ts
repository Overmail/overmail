import type {EmailBodyRepository} from "$lib/repository/EmailBodyRepository";
import type {EmailMeta, EmailRepository} from "$lib/repository/EmailRepository.svelte";
import {ReconnectingSocket, type SocketLike} from "$lib/repository/ReconnectingSocket";

const ENDPOINT = "/api/stack";

/** How few mails may be left below the current one before the next batch is asked for. */
const MAX_EMAILS_BEFORE_REFETCH = 5;

/** What the stack itself knows about a mail, on top of what the mail is. */
export type Classification = {type: "keep"} | {type: "archive"};

export type EmailBody = {
    text: string | null;
    html: string | null;
};

/** One card: the mail as the server has it, plus the body and what happened to it here. */
export type StackEmail = EmailMeta & {
    body: EmailBody;
    classification: Classification | null;
};

/**
 * The pile of mails, one card at a time.
 *
 * Two sources feed this: the stack socket says *which* mails are on the pile and in what order,
 * and the email repository says what each of them is -- subject, sender, labels -- and keeps that
 * current for as long as the card is here. Nothing about a mail is stored twice; what this holds
 * is the order, the bodies, and which cards have been handled.
 */
export class EmailStackViewModel {
    /** The pile, newest first, as the socket handed it out. */
    private ids: string[] = $state([]);

    /** Bodies, by mail. A card waits for its own before it is shown. */
    private bodies: Record<string, EmailBody> = $state({});

    /** Cards the reader has dealt with, and how. Local: the pile animates them away. */
    private handled: Record<string, Classification> = $state({});

    /** The subscription of every mail on the pile, so they can be let go of together. */
    private readonly releases = new Map<string, () => void>();

    /** What the reader picked, which may be a card that is not drawable (yet). */
    private selectedId: string | null = $state(null);

    /**
     * The cards to draw, newest first.
     *
     * A mail appears once it is known and its body is here; one that left the mailbox without
     * this reader doing it -- the agent filing it as spam -- disappears, while one handled here
     * stays so the pile can animate it away.
     */
    emails: StackEmail[] = $derived(
        this.ids
            .map((id): StackEmail | null => {
                const meta = this.mails.peek(id).value;
                const body = this.bodies[id];
                if (meta === null || body === undefined) return null;

                const classification = this.handled[id] ?? null;
                if (meta.archiveState !== "unarchive" && classification === null) return null;

                return {...meta, body, classification};
            })
            .filter((email): email is StackEmail => email !== null)
    );

    /**
     * The card on top. Always one that is actually drawn: a pick whose mail is still loading --
     * or turned out to be gone -- would otherwise leave the pile stuck on a card that is not
     * there, with nothing to move on from.
     */
    currentEmailId: string | null = $derived(
        this.emails.some((email) => email.id === this.selectedId)
            ? this.selectedId
            : (this.emails[0]?.id ?? null)
    );

    currentEmail = $derived(this.emails.find((email) => email.id === this.currentEmailId));
    currentEmailIndex = $derived(this.emails.findIndex((email) => email.id === this.currentEmailId));

    private readonly socket: StackSocket;

    constructor(
        private readonly mails: EmailRepository,
        private readonly bodyRepository: EmailBodyRepository,
        open?: (url: string) => SocketLike,
    ) {
        this.socket = new StackSocket({
            onEmailIds: (ids) => this.receive(ids),
            open,
        });
        this.socket.start();
    }

    dispose() {
        this.socket.stop();
        this.releases.forEach((release) => release());
        this.releases.clear();
    }

    onKeepEmail() {
        const id = this.currentEmailId;
        if (!id) return;
        this.handled[id] = {type: "keep"};

        this.onNextEmail();
    }

    onArchiveOrUnarchiveEmail() {
        const id = this.currentEmailId;
        if (!id) return;

        if (this.handled[id]?.type === "archive") {
            delete this.handled[id];
            this.socket.unarchiveEmail(id);
        } else {
            this.handled[id] = {type: "archive"};
            this.socket.archiveEmail(id);
        }

        this.onNextEmail();
    }

    onNextEmail() {
        if (this.currentEmailIndex === -1) return;
        if (this.currentEmailIndex + 1 >= this.emails.length) return;
        this.selectedId = this.emails[this.currentEmailIndex + 1].id;

        const remainingEmails = this.emails.length - (this.currentEmailIndex + 1);
        if (remainingEmails <= MAX_EMAILS_BEFORE_REFETCH) {
            this.socket.requestNextBatch();
        }
    }

    onPreviousEmail() {
        if (this.currentEmailIndex === -1) return;
        if (this.currentEmailIndex - 1 < 0) return;
        this.selectedId = this.emails[this.currentEmailIndex - 1].id;
    }

    async onRequestEmailClassification(emailId: string) {
        const result = await fetch(`/api/emails/${emailId}/classify`, {
            method: "POST",
        });
        return result.ok;
    }

    /**
     * A batch of the pile. Ids that are already here are skipped: a batch starts again with the
     * mail the last one ended on, and after a reconnect the socket starts over from the top.
     */
    private receive(ids: string[]) {
        for (const id of ids) {
            if (this.releases.has(id)) continue;

            // Kept up to date for as long as the card is on the pile; the body is fetched once,
            // because a mail's source does not change.
            this.releases.set(id, this.mails.subscribe(id));
            void this.loadBody(id);
            this.ids.push(id);
        }
    }

    private async loadBody(id: string) {
        try {
            this.bodies[id] = await this.bodyRepository.getBody(id);
        } catch (error) {
            console.error(`Failed to load the body of ${id}:`, error);
            // Shown without one rather than kept off the pile: the card is the mail, and a body
            // that cannot be loaded is not a reason to hide it.
            this.bodies[id] = {text: null, html: null};
        }
    }
}

type StackServerMessage = {
    type: "data.emails";
    email_ids: string[];
};

type StackClientMessage =
    | {type: "request.emails"}
    | {type: "update.email.archive"; email_id: string}
    | {type: "update.email.unarchive"; email_id: string};

/**
 * The pile's own socket: which mails are on it, and what the reader did with them.
 *
 * A fresh connection sends the top of the pile on its own, so there is nothing to ask for after a
 * reconnect -- but what the reader did while it was down is worth keeping, so writes wait for the
 * connection instead of being dropped.
 */
class StackSocket {
    private readonly socket: ReconnectingSocket<StackServerMessage>;
    private readonly queued: StackClientMessage[] = [];

    constructor(config: {
        onEmailIds: (ids: string[]) => void;
        open?: (url: string) => SocketLike;
    }) {
        this.socket = new ReconnectingSocket<StackServerMessage>({
            url: ENDPOINT,
            open: config.open,
            onOpen: () => {
                const waiting = this.queued.splice(0);
                waiting.forEach((message) => this.send(message));
            },
            onMessage: (message) => {
                switch (message.type) {
                    case "data.emails":
                        config.onEmailIds(message.email_ids);
                        break;
                }
            },
        });
    }

    start() {
        this.socket.start();
    }

    stop() {
        this.socket.stop();
    }

    requestNextBatch() {
        this.send({type: "request.emails"});
    }

    archiveEmail(emailId: string) {
        this.send({type: "update.email.archive", email_id: emailId});
    }

    unarchiveEmail(emailId: string) {
        this.send({type: "update.email.unarchive", email_id: emailId});
    }

    private send(message: StackClientMessage) {
        if (!this.socket.isOpen) {
            this.queued.push(message);
            return;
        }
        this.socket.send(message);
    }
}
