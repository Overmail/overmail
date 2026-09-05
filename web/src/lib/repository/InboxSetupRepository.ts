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
}
