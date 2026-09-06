/**
 * Why the probe could not reach an imap server, or `reachable` when it could. Mirrors
 * `ImapHostTestOutcome` on the server.
 *
 * `string & {}` keeps the union open: an outcome added on the server has to fall back to a
 * generic "did not work" here rather than break the build of a client that predates it.
 */
export type ImapHostOutcome =
    | "reachable"
    /** The host does not resolve -- a typo in the domain. */
    | "host_not_found"
    /** It resolves, but no connection came up on that port. */
    | "connection_failed"
    /** Something answers, but not with tls -- usually the plaintext port 143. */
    | "tls_failed"
    /** Tls held, but nothing greeted with `* OK`. */
    | "no_imap_server"
    /** Neither an answer nor a refusal in time. */
    | "timeout"
    | (string & {});

/** What `POST /api/users/me/inboxes/create/test/imap-host` answers. */
export type ImapHostTest = {
    reachable: boolean;
    outcome: ImapHostOutcome;
    /** What the server lists on `CAPABILITY`; empty unless [reachable]. */
    capabilities: string[];
};

const TEST_IMAP_HOST_ENDPOINT = "/api/users/me/inboxes/create/test/imap-host";
const TEST_IMAP_LOGIN_ENDPOINT = "/api/users/me/inboxes/create/test/imap-login";
const FOLDER_STREAM_ENDPOINT = "/api/users/me/inboxes/create/folders/stream";
const SUBMIT_ENDPOINT = "/api/users/me/inboxes/create/submit";

/** The connection an inbox is created for. */
export type InboxConnection = {
    host: string;
    port: number;
    username: string;
    password: string;
};

/**
 * How far back the assistant reads one folder, on the wire.
 *
 * `newest_messages` is the odd one: the server resolves the count to the date of the n-th newest
 * mail before storing it, because a count cannot be applied per mail as mail keeps arriving.
 */
export type WireAiScope =
    | {type: "only_new_messages"}
    | {type: "all_messages"}
    /** Seconds since the epoch, which is what the server reads it as. */
    | {type: "after_date"; timestamp: number}
    | {type: "newest_messages"; count: number};

/** One folder's settings as the submit endpoint takes them. */
export type SubmitInboxFolder = {
    folderName: string;
    /** Watch the folder over an open connection rather than only polling it. */
    imapPush: boolean;
    aiImport: WireAiScope;
};

/** What creating an inbox came to. A mailbox already set up is an answer, not a failure. */
export type SubmitInboxResult =
    | {type: "created"; id: string}
    | {type: "conflict"};

/** Why a login did not work, or `authenticated` when it did. Mirrors `ImapLoginTestOutcome`. */
export type ImapLoginOutcome =
    | "authenticated"
    /** The server read the credentials and said no. */
    | "invalid_credentials"
    /** The host answered a step ago and does not anymore. */
    | "connection_failed"
    | "timeout"
    | (string & {});

/** What `POST .../test/imap-login` answers. */
export type ImapLoginTest = {
    authenticated: boolean;
    outcome: ImapLoginOutcome;
};

/** One folder as the server's single `LIST` reports it, before anything has been counted. */
export type FolderNode = {
    /** The segments of the name. What the tree is built from; its length is the depth. */
    path: string[];
    /** [path] joined by [delimiter]. The id a [FolderStats] refers back to. */
    fullName: string;
    /** The last segment -- what a row shows. */
    name: string;
    delimiter: string;
    /** `INBOX`, `SENT`, `SPAM`, `TRASH`, `DRAFTS`, or null for an ordinary folder. */
    specialType: string | null;
};

/** What is in one folder. Nulls mean it could not be read; the folder still exists. */
export type FolderStats = {
    fullName: string;
    mailCount: number | null;
    /** ISO-8601, or null for an empty folder and for one that could not be read. */
    oldestMailAt: string | null;
};

/** What comes out of [InboxSetupRepository.streamFolders], in the order the server sends it. */
export type FolderStreamEvent =
    /** The whole tree by name, once, before any counting. The table is drawn from this. */
    | {type: "folders"; folders: FolderNode[]}
    /** One folder's numbers, as it is counted. */
    | {type: "stats"; stats: FolderStats}
    /** Every folder has been counted. */
    | {type: "done"}
    /** The mailbox could not be opened. Nothing follows this. */
    | {type: "error"; outcome: string};

/**
 * The checks the "new inbox" dialog runs against a form nobody has submitted yet.
 *
 * Nothing is cached: the answer is about a host the user is still typing, and a server that was
 * down a minute ago is exactly the one they will try again. Cancelling is the caller's job --
 * pass the [AbortSignal] of the keystroke this belongs to, or a slow answer to an old host
 * overwrites the fast answer to the current one.
 */
export class InboxSetupRepository {
    /**
     * Whether there is an imap server at [host]:[port]. Answers for every outcome; only a
     * request that did not happen at all throws.
     */
    async testImapHost(host: string, port: number, signal?: AbortSignal): Promise<ImapHostTest> {
        const response = await fetch(TEST_IMAP_HOST_ENDPOINT, {
            method: "POST",
            credentials: "include",
            headers: {"content-type": "application/json"},
            body: JSON.stringify({host, port}),
            signal,
        });
        if (!response.ok) throw new Error(`Could not test the imap host: ${response.status}`);

        const body = await response.json();
        return {
            reachable: body.reachable as boolean,
            outcome: body.outcome as ImapHostOutcome,
            capabilities: (body.capabilities ?? []) as string[],
        };
    }

    /**
     * Whether these credentials open that mailbox. Answers for every outcome; only a request
     * that did not happen at all throws.
     */
    async testImapLogin(
        host: string,
        port: number,
        username: string,
        password: string,
        signal?: AbortSignal,
        /** An existing mailbox, when the form is editing one: see [inboxEndpoint]. */
        inboxId?: string,
    ): Promise<ImapLoginTest> {
        const response = await fetch(inboxEndpoint(inboxId, "test/imap-login", TEST_IMAP_LOGIN_ENDPOINT), {
            method: "POST",
            credentials: "include",
            headers: {"content-type": "application/json"},
            body: JSON.stringify({host, port, username, password}),
            signal,
        });
        if (!response.ok) throw new Error(`Could not test the imap login: ${response.status}`);

        const body = await response.json();
        return {
            authenticated: body.authenticated as boolean,
            outcome: body.outcome as ImapLoginOutcome,
        };
    }

    /**
     * Creates the inbox, with one settings block per folder that is being kept.
     *
     * Answers rather than throws for a mailbox the user already has -- that is something to say
     * in the dialog, not a broken request. Anything else throws.
     */
    async submitInbox(
        imap: InboxConnection,
        folders: SubmitInboxFolder[],
        signal?: AbortSignal,
    ): Promise<SubmitInboxResult> {
        const response = await fetch(SUBMIT_ENDPOINT, {
            method: "POST",
            credentials: "include",
            headers: {"content-type": "application/json"},
            body: JSON.stringify({
                imap,
                folder_settings: folders.map((folder) => ({
                    folder_name: folder.folderName,
                    imap_push: folder.imapPush,
                    ai_import: folder.aiImport,
                })),
            }),
            signal,
        });

        if (response.status === 409) return {type: "conflict"};
        if (!response.ok) throw new Error(`Could not create the inbox: ${response.status}`);

        const body = await response.json();
        return {type: "created", id: body.id as string};
    }

    /**
     * Every folder of the mailbox and what is in it, as the server works through them.
     *
     * A POST that answers with an event stream, read here off the response body. `EventSource`
     * is not an option: it can only issue a GET, and these credentials must not end up in a
     * query string. Which also means no automatic reconnect -- correctly, since a resumed scan
     * would start the whole count over.
     *
     * Yields until `done` or `error`; abort [signal] to hang up early.
     */
    async *streamFolders(
        host: string,
        port: number,
        username: string,
        password: string,
        signal?: AbortSignal,
        /** An existing mailbox, when the form is editing one: see [inboxEndpoint]. */
        inboxId?: string,
    ): AsyncGenerator<FolderStreamEvent> {
        const response = await fetch(inboxEndpoint(inboxId, "folders/stream", FOLDER_STREAM_ENDPOINT), {
            method: "POST",
            credentials: "include",
            headers: {"content-type": "application/json"},
            body: JSON.stringify({host, port, username, password}),
            signal,
        });
        if (!response.ok || !response.body) {
            throw new Error(`Could not read the folders: ${response.status}`);
        }

        const reader = response.body.pipeThrough(new TextDecoderStream()).getReader();
        let buffer = "";
        try {
            while (true) {
                const {done, value} = await reader.read();
                if (done) break;
                buffer += value;

                // A frame is whatever stands before a blank line; anything after it is the
                // beginning of the next one and stays in the buffer.
                let boundary: number;
                while ((boundary = buffer.indexOf("\n\n")) !== -1) {
                    const frame = buffer.slice(0, boundary);
                    buffer = buffer.slice(boundary + 2);
                    const event = parseFrame(frame);
                    if (event) yield event;
                }
            }
        } finally {
            // Releasing the lock is not enough -- without the cancel the connection stays open
            // when a caller stops iterating early.
            await reader.cancel().catch(() => {});
        }
    }
}

/**
 * Where a check goes: the setup route, or the one for a mailbox that already exists.
 *
 * The two do the same thing and answer the same way. They differ in one respect only: given an
 * existing mailbox, the server fills in the stored password when the form sent none, which is
 * what lets an edit screen check anything at all without the password being re-typed.
 */
function inboxEndpoint(inboxId: string | undefined, path: string, whenCreating: string): string {
    if (!inboxId) return whenCreating;
    return `/api/users/me/inboxes/${encodeURIComponent(inboxId)}/${path}`;
}

/** One `data:` frame to an event, or null for the keep-alives and comments SSE allows. */
function parseFrame(frame: string): FolderStreamEvent | null {
    const data = frame
        .split("\n")
        .filter((line) => line.startsWith("data:"))
        .map((line) => line.slice("data:".length).trimStart())
        .join("\n");
    if (!data) return null;

    const body = JSON.parse(data);
    switch (body.type) {
        case "folders":
            return {
                type: "folders",
                folders: (body.folders as any[]).map((folder) => ({
                    path: folder.path as string[],
                    fullName: folder.full_name as string,
                    name: folder.name as string,
                    delimiter: folder.delimiter as string,
                    specialType: (folder.special_type ?? null) as string | null,
                })),
            };
        case "stats":
            return {
                type: "stats",
                stats: {
                    fullName: body.full_name as string,
                    mailCount: (body.mail_count ?? null) as number | null,
                    oldestMailAt: (body.oldest_mail_at ?? null) as string | null,
                },
            };
        case "done":
            return {type: "done"};
        case "error":
            return {type: "error", outcome: body.outcome as string};
        default:
            return null;
    }
}
