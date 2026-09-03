import {SvelteMap} from "svelte/reactivity";
import type {EntityTable} from "dexie";
import {entityCache, type CachedEmail, type CachedLabel, type CachedSender} from "$lib/app/entities/cache";

/** What a caller sees for one id. Null value plus not loading means: does not exist (for us). */
export type EntityEntry<T> = {
    value: T | null;
    isLoading: boolean;
};

const LOADING: EntityEntry<never> = {value: null, isLoading: true};
const MISSING: EntityEntry<never> = {value: null, isLoading: false};

/**
 * One place to ask what an entity is called, no matter who asks.
 *
 * Reads are reactive and never suspend: [peek] answers from what is already known, [request]
 * fills the gaps. Ids requested in the same tick are fetched in a single call, every id is
 * fetched at most once per session, and what comes back is written to [entityCache] so the next
 * visit starts with it instead of an empty screen.
 */
export class EntityRepository<T extends {id: string}> {
    private readonly entries = new SvelteMap<string, EntityEntry<T>>();

    /** Ids waiting for the next flush. */
    private readonly queued = new Set<string>();

    /** Ids already asked of the server in this session, so nothing is fetched twice. */
    private readonly requested = new Set<string>();

    private isFlushScheduled = false;

    constructor(
        private readonly config: {
            /** Where the ids go: `/api/emails`, `/api/labels`, ... */
            endpoint: string;
            /** The key the entities sit under in the response. */
            key: string;
            /** The table this kind is cached in, null while there is no IndexedDB. */
            table: () => EntityTable<T, "id"> | null;
            /** The api shape, in the app's own. */
            parse: (raw: any) => T;
        },
    ) {}

    /** What is known about [id] right now. Safe to call while rendering: it changes nothing. */
    peek(id: string): EntityEntry<T> {
        return this.entries.get(id) ?? LOADING;
    }

    /**
     * Makes sure [id] is on its way. Call this from an effect, not while rendering -- it starts
     * a load and writes state.
     */
    request(id: string) {
        if (this.requested.has(id)) return;
        this.requested.add(id);

        if (!this.entries.has(id)) this.entries.set(id, LOADING);

        // From the cache first, so a chip has its text before the network answers.
        void this.readFromCache(id);

        this.queued.add(id);
        this.scheduleFlush();
    }

    private async readFromCache(id: string) {
        const table = this.config.table();
        if (!table) return;

        try {
            // Through the index rather than table.get: the key type of a generic table is not
            // known to be a string here.
            const cached = await table.where("id").equals(id).first();
            // A fresher answer may have overtaken the cache; that one wins.
            if (cached && this.entries.get(id)?.isLoading !== false) {
                this.entries.set(id, {value: cached, isLoading: true});
            }
        } catch (error) {
            console.error(`Failed to read ${this.config.key} ${id} from the cache:`, error);
        }
    }

    private scheduleFlush() {
        if (this.isFlushScheduled) return;
        this.isFlushScheduled = true;

        // One call for everything a render asked for: a message with ten chips is one request.
        queueMicrotask(() => {
            this.isFlushScheduled = false;
            const ids = [...this.queued];
            this.queued.clear();
            if (ids.length > 0) void this.fetch(ids);
        });
    }

    private async fetch(ids: string[]) {
        try {
            const response = await fetch(`${this.config.endpoint}?ids=${ids.join(",")}`);
            if (!response.ok) {
                console.error(`Failed to load ${this.config.key}: ${response.status} ${response.statusText}`);
                // Not marked as missing: the ids stay unknown, and a reload may find them.
                this.forget(ids);
                return;
            }

            const data = await response.json();
            const entities: T[] = (data[this.config.key] ?? []).map(this.config.parse);

            for (const entity of entities) this.entries.set(entity.id, {value: entity, isLoading: false});

            // Everything the server did not return does not exist for this user -- deleted since,
            // or never theirs. The cache must not keep it either.
            const found = new Set(entities.map((entity) => entity.id));
            const gone = ids.filter((id) => !found.has(id));
            for (const id of gone) this.entries.set(id, MISSING);

            void this.writeToCache(entities, gone);
        } catch (error) {
            console.error(`Failed to load ${this.config.key}:`, error);
            this.forget(ids);
        }
    }

    /** Drops what could not be loaded, so a later request tries again instead of hanging. */
    private forget(ids: string[]) {
        for (const id of ids) {
            this.requested.delete(id);
            this.entries.delete(id);
        }
    }

    private async writeToCache(entities: T[], gone: string[]) {
        const table = this.config.table();
        if (!table) return;

        try {
            if (entities.length > 0) await table.bulkPut(entities);
            if (gone.length > 0) await table.where("id").anyOf(gone).delete();
        } catch (error) {
            console.error(`Failed to cache ${this.config.key}:`, error);
        }
    }
}

export const emailRepository = new EntityRepository<CachedEmail>({
    endpoint: "/api/emails",
    key: "emails",
    table: () => entityCache?.emails ?? null,
    parse: (raw) => ({
        id: raw.id,
        subject: raw.subject,
        senderId: raw.sender_id,
        senderName: raw.sender_name,
        senderAddress: raw.sender_address,
        avatarUrl: raw.avatar_url,
        avatarPadding: raw.avatar_padding,
        sent: raw.sent,
        isRead: raw.is_read,
    }),
});

export const labelRepository = new EntityRepository<CachedLabel>({
    endpoint: "/api/labels",
    key: "labels",
    table: () => entityCache?.labels ?? null,
    parse: (raw) => ({id: raw.id, name: raw.name, color: raw.color}),
});

export const senderRepository = new EntityRepository<CachedSender>({
    endpoint: "/api/senders",
    key: "senders",
    table: () => entityCache?.senders ?? null,
    parse: (raw) => ({
        id: raw.id,
        name: raw.name,
        address: raw.address,
        avatarUrl: raw.avatar_url,
        avatarPadding: raw.avatar_padding,
    }),
});
