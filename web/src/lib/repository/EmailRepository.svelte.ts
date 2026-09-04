import {ReconnectingSocket, type SocketLike} from "$lib/repository/ReconnectingSocket";

const ENDPOINT = "/api/webapp/content/socket";

/**
 * How long a mail stays subscribed after the last screen let go of it.
 *
 * Scrolling a table walks rows in and out several times a second, and a subscription that follows
 * that exactly would be a message per row per flick. It also means going back to a mail that just
 * left the window paints from what is already here.
 */
const RELEASE_GRACE_MS = 10_000;

export type EmailLabel = {
    id: string;
    name: string;
    color: string;
    description: string | null;
    /** Why the agent attached it, when it was the agent. */
    assignmentReason: string | null;
    createdByAgent: boolean;
};

/** Somebody a mail is from or to, as the address book has them. */
export type EmailParticipant = {
    id: string;
    /** Display name from this mail's header, absent for a bare address. */
    name: string | null;
    address: string;
    avatarUrl: string | null;
    /** How much of its box the picture gives up to fit a circle; null when none. */
    avatarPadding: number | null;
};

/** Everything a screen shows of a mail without opening it. The body is fetched separately. */
export type EmailMeta = {
    id: string;
    subject: string;
    /** Unix seconds. */
    sent: number;
    isRead: boolean;
    /**
     * How the body begins, as one line. Null for a mail whose body nothing has looked at yet;
     * empty for one with nothing readable in it.
     */
    preview: string | null;
    /** Where the mail stands; anything but `unarchive` is out of the mailbox. */
    archiveState: "unarchive" | "archive" | "spam";
    sender: EmailParticipant;
    to: EmailParticipant[];
    cc: EmailParticipant[];
    bcc: EmailParticipant[];
    labels: EmailLabel[];
};

/** What a caller sees for one id. Null value plus not loading means: not there (for us). */
export type EmailEntry = {
    value: EmailMeta | null;
    isLoading: boolean;
};

const LOADING: EmailEntry = {value: null, isLoading: true};
const MISSING: EmailEntry = {value: null, isLoading: false};

type ContentServerMessage =
    | {type: "data.emails"; emails: WireEmail[]}
    | {type: "data.emails.unknown"; ids: string[]}
    | {type: "update.mails.moved"};

type WireParticipant = {
    id: string;
    name: string | null;
    address: string;
    avatar_url: string | null;
    avatar_padding: number | null;
};

type WireEmail = {
    id: string;
    subject: string;
    sent: number;
    is_read: boolean;
    preview: string | null;
    archive_state: EmailMeta["archiveState"];
    sender: WireParticipant;
    to: WireParticipant[];
    cc: WireParticipant[];
    bcc: WireParticipant[];
    labels: {
        id: string;
        name: string;
        color: string;
        description: string | null;
        assignment_reason: string | null;
        created_by_agent: boolean;
    }[];
};

/**
 * The mails the app is showing, kept current by the content socket.
 *
 * Reads are reactive and never suspend: [peek] answers from what is already known, [subscribe]
 * puts a mail on the socket and hands back the release. A mail is subscribed once however many
 * screens show it, and it is dropped a while after the last of them let go.
 *
 * What arrives is the whole metadata of a mail, so a message is merged over what was there and
 * nothing has to be told apart -- a snapshot, an update, and what another feed already knew are
 * the same thing here (see [merge]).
 */
export class EmailRepository {
    /** What is known per id. A record rather than a map: one reactivity mechanism, and runes
     *  are the one that a test can drive without a browser. */
    private entries: Record<string, EmailEntry> = $state({});

    /**
     * How often the server has said that this user's mail moved -- one arrived, or one left a
     * listing by being archived or filed. Zero until the first announcement.
     *
     * A number rather than what changed, because that is all the server sends: what a listing is
     * is a query, and a mail that just arrived is at the top of one without anybody having been
     * able to subscribe to it. Whoever holds positions reads this and asks again; what a *mail*
     * is arrives as metadata like everything else.
     */
    revision: number = $state(0);

    /** How many callers hold [id]. A mail is on the socket while this is above zero. */
    private readonly watchers = new Map<string, number>();

    /** How many callers watch [revision] without holding a mail of their own; see [watchMoves]. */
    private moveWatchers = 0;

    /** Ids whose grace period is running, with the timer that ends it. */
    private readonly releasing = new Map<string, ReturnType<typeof setTimeout>>();

    /** Collected per tick, so a screen full of rows is one message. */
    private readonly queuedSubscribes = new Set<string>();
    private readonly queuedUnsubscribes = new Set<string>();
    private isFlushScheduled = false;

    private readonly socket: ReconnectingSocket<ContentServerMessage>;
    private readonly graceMs: number;

    constructor(config: {
        /** Defaults to a real browser socket. */
        open?: (url: string) => SocketLike;
        reconnectDelays?: number[];
        /** Overridden in tests, which have no ten seconds to wait. */
        graceMs?: number;
    } = {}) {
        this.graceMs = config.graceMs ?? RELEASE_GRACE_MS;
        this.socket = new ReconnectingSocket<ContentServerMessage>({
            url: ENDPOINT,
            open: config.open,
            reconnectDelays: config.reconnectDelays,
            // The server's side of a subscription died with the old connection, so everything on
            // screen is asked for again -- which is also how it gets a fresh snapshot of what
            // changed while there was no connection.
            onOpen: () => {
                const subscribed = [...this.watchers.keys(), ...this.releasing.keys()];
                if (subscribed.length > 0) this.send("subscribe.emails", subscribed);
            },
            onMessage: (message) => this.receive(message),
        });
    }

    /** What is known about [id] right now. Safe to call while rendering: it changes nothing. */
    peek(id: string): EmailEntry {
        return this.entries[id] ?? LOADING;
    }

    /**
     * Keeps [id] up to date until the returned function is called. From an effect, not while
     * rendering -- it opens a socket and writes state:
     *
     * ```svelte
     * $effect(() => emails.subscribe(id));
     * ```
     */
    subscribe(id: string): () => void {
        const grace = this.releasing.get(id);
        if (grace !== undefined) {
            clearTimeout(grace);
            this.releasing.delete(id);
        }

        const watchers = (this.watchers.get(id) ?? 0) + 1;
        this.watchers.set(id, watchers);

        if (this.entries[id] === undefined) this.entries[id] = LOADING;

        // Only the first watcher subscribes; a grace period that was still running means the
        // server never stopped sending it, so there is nothing to ask for either.
        if (watchers === 1 && grace === undefined) {
            this.queuedSubscribes.add(id);
            this.queuedUnsubscribes.delete(id);
            this.scheduleFlush();
        }

        this.socket.start();

        let released = false;
        return () => {
            // Guarded: an effect that re-runs must not release the same handle twice and drop a
            // mail somebody else is still watching.
            if (released) return;
            released = true;
            this.release(id);
        };
    }

    /**
     * Keeps the socket up for [revision] alone, until the returned function is called. From an
     * effect, like [subscribe].
     *
     * What a listing needs beyond the mails it shows: an empty mailbox subscribes to nothing at
     * all, the socket would be closed, and that is exactly when the first mail arriving is the
     * one worth hearing about.
     */
    watchMoves(): () => void {
        this.moveWatchers++;
        this.socket.start();

        let released = false;
        return () => {
            if (released) return;
            released = true;
            this.moveWatchers--;
            // Nothing may be left watching anything, and then the socket goes -- the same check
            // the last released mail runs through.
            this.scheduleFlush();
        };
    }

    /**
     * Whether the reader has seen [id]: opening a mail reads it, and a button takes that back.
     *
     * Nothing is checked against what is known about the mail here: the route is idempotent and
     * answers the same whether it wrote or not, so asking for a state a mail is already in costs
     * one request that writes nothing. Deciding that from this copy instead would mean a click
     * doing nothing at all whenever the copy is behind, which is the one outcome a button must
     * not have. The same goes for [setArchiveStateTo].
     */
    async setRead(id: string, isRead: boolean): Promise<void> {
        const response = await fetch(
            `/api/emails/${id}/${isRead ? "read" : "unread"}`,
            {method: "POST"},
        );

        this.afterWrite(id, response);
    }

    /** Where [id] stands: in the mailbox, archived, or filed as spam. */
    async setArchiveState(id: string, state: EmailMeta["archiveState"]): Promise<void> {
        // `unarchive`, `archive`, `spam`: the state a mail is in is the route that puts it there.
        const response = await fetch(`/api/emails/${id}/${state}`, {method: "POST"});

        this.afterWrite(id, response);
    }

    /**
     * Hangs a label that exists on [id], or takes it off again.
     *
     * The pair of ids is the whole address of it -- see `EmailLabels` on the server -- so this
     * needs nothing but the label a caller already holds, and the same as above applies: what a
     * mail carries afterwards is read again rather than assumed.
     */
    async attachLabel(id: string, labelId: string): Promise<void> {
        const response = await fetch(`/api/emails/${id}/labels/${labelId}`, {method: "POST"});

        this.afterWrite(id, response);
    }

    async detachLabel(id: string, labelId: string): Promise<void> {
        const response = await fetch(`/api/emails/${id}/labels/${labelId}`, {method: "DELETE"});

        this.afterWrite(id, response);
    }

    /**
     * A label of that name on [id]: created and attached in one request, and a name this user
     * already has is that label rather than a second one of the same name.
     *
     * No colour goes out: the server picks the one the name derives to, the same as for the
     * labels the agent makes. `attach_to_email_ids` takes a list because that is what a
     * selection of mails will need; here it is the one mail that is open.
     */
    async createLabelOn(id: string, name: string): Promise<void> {
        const response = await fetch("/api/labels", {
            method: "POST",
            headers: {"Content-Type": "application/json"},
            body: JSON.stringify({name, attach_to_email_ids: [id]}),
        });

        this.afterWrite(id, response);
    }

    /**
     * Puts [id] in front of the classification agent again: `POST /api/emails/{id}/classify`.
     *
     * Answers whether the server took it, and that is all there is to wait for -- what the agent
     * makes of the mail arrives over the socket as its labels, whenever the run gets to it. The
     * mail itself is unchanged by the request, so nothing is read again here.
     */
    async requestClassification(id: string): Promise<boolean> {
        const response = await fetch(`/api/emails/${id}/classify`, {method: "POST"});
        if (!response.ok) {
            console.error(`Could not classify mail ${id}: ${response.status} ${response.statusText}`);
        }

        return response.ok;
    }

    /**
     * What follows any of the writes above.
     *
     * The mail is read again rather than assumed, see [requestSnapshot]: nothing about it is
     * written here, and the screen that pressed the button must not be the last one to hear what
     * it did.
     */
    private afterWrite(id: string, response: Response) {
        if (!response.ok) {
            // Nothing to do about it here: the mail stays as it is, which is what it is.
            console.error(`Could not write mail ${id}: ${response.status} ${response.statusText}`);
            return;
        }

        this.requestSnapshot(id);
    }

    /**
     * Asks the socket for the metadata of [id] again. A subscription the server already has is
     * answered as well -- with a fresh snapshot, which is the point.
     *
     * Only for a mail this client is watching: subscribing to one nothing holds would leave it on
     * the server's side of the socket with nothing here to let go of it again.
     */
    private requestSnapshot(id: string) {
        if (!this.watchers.has(id) && !this.releasing.has(id)) return;

        this.send("subscribe.emails", [id]);
    }

    /**
     * Takes mails another feed already has -- a listing that arrives with its rows, say -- into
     * the same store, so a screen does not wait for the socket to say what it was just told.
     */
    merge(mails: EmailMeta[]) {
        for (const mail of mails) this.entries[mail.id] = {value: mail, isLoading: false};
    }

    private release(id: string) {
        const watchers = (this.watchers.get(id) ?? 1) - 1;
        if (watchers > 0) {
            this.watchers.set(id, watchers);
            return;
        }

        this.watchers.delete(id);
        // Kept for a while: whoever let go may be back within the same flick of a scrollbar.
        this.releasing.set(
            id,
            setTimeout(() => {
                this.releasing.delete(id);
                this.queuedUnsubscribes.add(id);
                this.queuedSubscribes.delete(id);
                this.scheduleFlush();
            }, this.graceMs)
        );
    }

    private scheduleFlush() {
        if (this.isFlushScheduled) return;
        this.isFlushScheduled = true;

        queueMicrotask(() => {
            this.isFlushScheduled = false;

            const subscribes = [...this.queuedSubscribes];
            const unsubscribes = [...this.queuedUnsubscribes];
            this.queuedSubscribes.clear();
            this.queuedUnsubscribes.clear();

            if (subscribes.length > 0) this.send("subscribe.emails", subscribes);
            if (unsubscribes.length > 0) this.send("unsubscribe.emails", unsubscribes);

            // Nothing on screen any more: the connection goes with it, and the next subscription
            // opens a new one that starts by asking for what it wants.
            if (this.moveWatchers === 0 && this.watchers.size === 0 && this.releasing.size === 0) {
                this.socket.stop();
            }
        });
    }

    private send(type: "subscribe.emails" | "unsubscribe.emails", ids: string[]) {
        this.socket.send({type, ids});
    }

    private receive(message: ContentServerMessage) {
        switch (message.type) {
            case "data.emails":
                this.merge(message.emails.map(parse));
                break;
            case "data.emails.unknown":
                // Gone, never existed, or not ours -- the same answer either way, and the same
                // one a caller gets for an id it made up.
                for (const id of message.ids) this.entries[id] = MISSING;
                break;
            case "update.mails.moved":
                this.revision++;
                break;
        }
    }
}

function parseParticipant(participant: WireParticipant): EmailParticipant {
    return {
        id: participant.id,
        name: participant.name,
        address: participant.address,
        avatarUrl: participant.avatar_url,
        avatarPadding: participant.avatar_padding,
    };
}

/** The api shape, in the app's own. */
function parse(mail: WireEmail): EmailMeta {
    return {
        id: mail.id,
        subject: mail.subject,
        sent: mail.sent,
        isRead: mail.is_read,
        preview: mail.preview,
        archiveState: mail.archive_state,
        sender: parseParticipant(mail.sender),
        to: mail.to.map(parseParticipant),
        cc: mail.cc.map(parseParticipant),
        bcc: mail.bcc.map(parseParticipant),
        labels: mail.labels.map((label) => ({
            id: label.id,
            name: label.name,
            color: label.color,
            description: label.description,
            assignmentReason: label.assignment_reason,
            createdByAgent: label.created_by_agent,
        })),
    };
}
