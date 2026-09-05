import type {ImapHostOutcome, InboxSetupRepository} from "$lib/repository/InboxSetupRepository";

/**
 * How long the form stays quiet before it asks the server.
 *
 * Every test opens a real connection to somebody else's mail server, so this is not about saving
 * a round trip -- it is about not knocking on `imap.exampl`, `imap.example`, `imap.example.c` on
 * the way to `imap.example.com`.
 */
const TEST_DEBOUNCE_MS = 500;

/** What imap over tls runs on, and what the field starts on. */
export const DEFAULT_IMAP_PORT = 993;

/** Where the connection test stands, for the host and port currently in the form. */
export type ImapServerTest =
    /** Nothing to test yet -- no host, or a port that is not one. */
    | {type: "idle"}
    | {type: "testing"}
    | {type: "reachable"; capabilities: string[]}
    /** The server answered, and the answer is no. [outcome] says which no. */
    | {type: "unreachable"; outcome: ImapHostOutcome}
    /** The test itself did not happen -- offline, or the server is down. Says nothing about the host. */
    | {type: "failed"};

/**
 * The "new inbox" form.
 *
 * Host and port are checked as they are typed: they are the pair that can be wrong on their own,
 * and finding out after the password has been entered and submitted is one round of typing too
 * late. Nothing here submits anything yet.
 *
 * Only ever one test is outstanding. Editing the form cancels what was scheduled *and* what is
 * already on its way -- otherwise a slow answer about the host from two keystrokes ago lands on
 * top of the fast answer about the current one.
 */
export class NewEmailAccountViewModel {
    host = $state("");
    port = $state(DEFAULT_IMAP_PORT);
    imapServerTest: ImapServerTest = $state({type: "idle"});

    #debounce: ReturnType<typeof setTimeout> | null = null;
    #running: AbortController | null = null;

    constructor(private readonly inboxSetup: InboxSetupRepository) {}

    setHost(value: string) {
        this.host = value;
        this.#scheduleTest();
    }

    setPort(value: number) {
        this.port = value;
        this.#scheduleTest();
    }

    /** Called when the dialog closes, so a pending test does not outlive it. */
    dispose() {
        this.#cancelPendingTest();
    }

    #cancelPendingTest() {
        if (this.#debounce !== null) clearTimeout(this.#debounce);
        this.#debounce = null;
        this.#running?.abort();
        this.#running = null;
    }

    #scheduleTest() {
        this.#cancelPendingTest();

        const host = this.host.trim();
        const port = this.port;
        // The server rejects these, and there is nothing to say about a form this empty anyway.
        if (host === "" || !Number.isInteger(port) || port < 1 || port > 65535) {
            this.imapServerTest = {type: "idle"};
            return;
        }

        this.#debounce = setTimeout(() => void this.#testImapServer(host, port), TEST_DEBOUNCE_MS);
    }

    async #testImapServer(host: string, port: number) {
        this.#debounce = null;
        const running = new AbortController();
        this.#running = running;
        this.imapServerTest = {type: "testing"};

        try {
            const result = await this.inboxSetup.testImapHost(host, port, running.signal);
            // A newer test took over while this one was on its way; its answer is the current one.
            if (running.signal.aborted) return;
            this.imapServerTest = result.reachable
                ? {type: "reachable", capabilities: result.capabilities}
                : {type: "unreachable", outcome: result.outcome};
        } catch {
            if (running.signal.aborted) return;
            this.imapServerTest = {type: "failed"};
        } finally {
            if (this.#running === running) this.#running = null;
        }
    }
}
