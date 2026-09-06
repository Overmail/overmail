/** One connected mailbox, as `GET /api/users/me/inboxes` reports it. */
export type Inbox = {
    id: string;
    host: string;
    port: number;
    /** The imap login, which for most providers is the address itself. */
    username: string;
    /** The folders being synced, by name, alphabetically. */
    folders: string[];
};

const ENDPOINT = "/api/users/me/inboxes";

/**
 * The mailboxes this user has connected.
 *
 * Nothing is cached: the list is read when a screen shows it and again when something changed it,
 * and both of those are moments where a stale answer would be the wrong one. It is one short
 * query behind one request.
 */
export class InboxRepository {
    async list(signal?: AbortSignal): Promise<Inbox[]> {
        const response = await fetch(ENDPOINT, {credentials: "include", signal});
        if (!response.ok) throw new Error(`Could not read the inboxes: ${response.status}`);

        const body = await response.json();
        return (body.inboxes as any[]).map((inbox) => ({
            id: inbox.id as string,
            host: inbox.host as string,
            port: inbox.port as number,
            username: inbox.username as string,
            folders: (inbox.folders ?? []) as string[],
        }));
    }
}
