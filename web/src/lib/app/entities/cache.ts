import Dexie, {type EntityTable} from "dexie";

/** A mail as the app shows it in passing: subject line, who sent it, when. */
export type CachedEmail = {
    id: string;
    subject: string;
    senderId: string;
    senderName: string | null;
    senderAddress: string;
    avatarUrl: string | null;
    /** Unix seconds. */
    sent: number;
    isRead: boolean;
};

export type CachedLabel = {
    id: string;
    name: string;
    color: string;
};

export type CachedSender = {
    id: string;
    name: string | null;
    address: string;
    avatarUrl: string | null;
};

/**
 * What the entity repositories keep between visits.
 *
 * Only what a chip needs, and only ever as a copy of the server's state: everything in here is
 * re-fetched once per session and replaced with what comes back, so a renamed label or a new
 * avatar cannot be shown stale for longer than that.
 */
export class EntityCache extends Dexie {
    emails!: EntityTable<CachedEmail, "id">;
    labels!: EntityTable<CachedLabel, "id">;
    senders!: EntityTable<CachedSender, "id">;

    constructor() {
        super("overmail-entities");
        this.version(1).stores({
            emails: "id",
            labels: "id",
            senders: "id",
        });
    }
}

/**
 * Null wherever there is no IndexedDB -- server rendering, and unit tests. The repositories then
 * simply go to the network every time.
 */
export const entityCache = typeof indexedDB === "undefined" ? null : new EntityCache();
