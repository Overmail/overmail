/** One thing the assistant knows about the user, as `GET /api/users/me/knowledge` reports it. */
export type KnowledgeEntry = {
    id: string;
    /** What the entry is about, in a few words. Also its handle -- unique per user. */
    name: string;
    description: string;
    /** The words the agent finds this entry by, normalized to lowercase by the server. */
    keywords: string[];
    /** `YYYY-MM-DD` for the entries that are about a day -- a deadline, a move. Usually null. */
    relevantOn: string | null;
    createdAt: string;
    updatedAt: string;
    /** Whether the assistant learned this itself, rather than the user writing it down. */
    createdByAgent: boolean;
};

/** What a screen sends to write an entry. The whole entry, not a change to it. */
export type KnowledgeDraft = {
    name: string;
    description: string;
    keywords: string[];
    relevantOn: string | null;
};

const ENDPOINT = "/api/users/me/knowledge";

/** A write refused because an entry of that name is already there. */
export class KnowledgeNameTakenError extends Error {
    constructor() {
        super("There is already an entry of that name");
    }
}

/**
 * What the assistant knows about this user, as the settings screen reads and corrects it.
 *
 * Nothing is cached, like [InboxRepository]: the list is read when the screen shows it and again
 * when something changed it. The agent writes to the same rows while it classifies mail, so a
 * list held across a session would be stale in a way nothing here could notice.
 */
export class KnowledgeRepository {
    async list(signal?: AbortSignal): Promise<KnowledgeEntry[]> {
        const response = await fetch(ENDPOINT, {credentials: "include", signal});
        if (!response.ok) throw new Error(`Could not read the knowledge: ${response.status}`);

        const body = await response.json();
        return (body.knowledge as any[]).map(toEntry);
    }

    /** Writes an entry the user typed. Throws [KnowledgeNameTakenError] on a name that is used. */
    async create(draft: KnowledgeDraft, signal?: AbortSignal): Promise<KnowledgeEntry> {
        const response = await fetch(ENDPOINT, {
            method: "POST",
            credentials: "include",
            headers: {"content-type": "application/json"},
            body: JSON.stringify(toBody(draft)),
            signal,
        });

        if (response.status === 409) throw new KnowledgeNameTakenError();
        if (!response.ok) throw new Error(`Could not write the entry: ${response.status}`);
        return toEntry(await response.json());
    }

    /**
     * Saves an edited entry.
     *
     * [draft] is the whole entry the screen is showing, not a change to it: a keyword left out is
     * one the user removed. The id stays, so renaming is an edit and not a second entry.
     */
    async update(id: string, draft: KnowledgeDraft, signal?: AbortSignal): Promise<KnowledgeEntry> {
        const response = await fetch(`${ENDPOINT}/${encodeURIComponent(id)}`, {
            method: "PUT",
            credentials: "include",
            headers: {"content-type": "application/json"},
            body: JSON.stringify(toBody(draft)),
            signal,
        });

        if (response.status === 409) throw new KnowledgeNameTakenError();
        if (!response.ok) throw new Error(`Could not save the entry: ${response.status}`);
        return toEntry(await response.json());
    }

    /** Forgets an entry. Nothing hangs off one, so this takes the row and nothing else. */
    async remove(id: string, signal?: AbortSignal): Promise<void> {
        const response = await fetch(`${ENDPOINT}/${encodeURIComponent(id)}`, {
            method: "DELETE",
            credentials: "include",
            signal,
        });
        if (!response.ok) throw new Error(`Could not delete the entry: ${response.status}`);
    }
}

function toEntry(entry: any): KnowledgeEntry {
    return {
        id: entry.id as string,
        name: entry.name as string,
        description: (entry.description ?? "") as string,
        keywords: (entry.keywords ?? []) as string[],
        relevantOn: (entry.relevant_on ?? null) as string | null,
        createdAt: entry.created_at as string,
        updatedAt: entry.updated_at as string,
        createdByAgent: (entry.created_by_agent ?? false) as boolean,
    };
}

function toBody(draft: KnowledgeDraft) {
    return {
        name: draft.name,
        description: draft.description,
        keywords: draft.keywords,
        relevant_on: draft.relevantOn,
    };
}
