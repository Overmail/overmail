import {
    KnowledgeNameTakenError,
    type KnowledgeDraft,
    type KnowledgeEntry,
    type KnowledgeRepository,
} from "$lib/repository/KnowledgeRepository";

/**
 * How many keywords an entry keeps.
 *
 * The same number as `MAX_KEYWORDS` in [NewKnowledgeViewModel.svelte.ts] and
 * `Knowledge.MAX_KEYWORDS` on the server. A row adds keywords without going through the form's
 * view model, and the server cuts the rest off silently, so the limit has to hold here too.
 */
export const MAX_KEYWORDS = 24;

/**
 * A keyword as the server stores it; see `Knowledge.normalizeKeyword`, and the private copy of
 * this in the form's view model.
 */
export function normalizeKeyword(keyword: string): string {
    return keyword.trim().toLowerCase().replace(/\s+/g, " ");
}

/**
 * The list with [raw] added, or null when adding it changes nothing -- it is empty, it is already
 * there, or the entry is at its limit. Null is what tells the row not to write to the server.
 */
export function withKeyword(keywords: string[], raw: string): string[] | null {
    const keyword = normalizeKeyword(raw);
    if (keyword.length === 0) return null;
    if (keywords.includes(keyword)) return null;
    if (keywords.length >= MAX_KEYWORDS) return null;
    return [...keywords, keyword];
}

export function withoutKeyword(keywords: string[], keyword: string): string[] {
    return keywords.filter((existing) => existing !== keyword);
}

/** Which cell a save belongs to, so only that one says it is saving, or why it did not. */
export type InlineField = "name" | "description" | "keywords";

/** Why a save did not happen. [nameTaken] is the one the user fixes by typing something else. */
export type InlineFailure = "nameTaken" | "unknown";

export type InlineSaveState =
    | {type: "idle"}
    | {type: "saving", field: InlineField}
    | {type: "failed", field: InlineField, reason: InlineFailure};

/**
 * Writes one changed field of an entry back, from the table row itself.
 *
 * One of these per row rather than one per cell: `update` sends the whole entry, so two cells
 * saving at the same time would each overwrite the other's change with the copy it started from.
 * That is also why only one save runs at a time.
 */
export class InlineEditing {
    state: InlineSaveState = $state({type: "idle"});

    constructor(private readonly repository: Pick<KnowledgeRepository, "update">) {}

    savingIn(field: InlineField): boolean {
        return this.state.type === "saving" && this.state.field === field;
    }

    /** Why the last save into [field] did not happen, or null. */
    failureIn(field: InlineField): InlineFailure | null {
        return this.state.type === "failed" && this.state.field === field ? this.state.reason : null;
    }

    /** Typing is the answer to the message under the field, so the next keystroke takes it down. */
    clearFailure(field: InlineField) {
        if (this.state.type === "failed" && this.state.field === field) this.state = {type: "idle"};
    }

    /**
     * Sends the entry with [change] applied and answers with what the server stored.
     *
     * Null means it was refused; the caller then leaves its editor standing with what was typed
     * still in it, and [failureIn] says why.
     */
    async save(
        entry: KnowledgeEntry,
        field: InlineField,
        change: Partial<KnowledgeDraft>,
    ): Promise<KnowledgeEntry | null> {
        if (this.state.type === "saving") return null;

        const draft: KnowledgeDraft = {
            name: entry.name,
            description: entry.description,
            keywords: entry.keywords,
            relevantOn: entry.relevantOn,
            ...change,
        };

        this.state = {type: "saving", field};
        try {
            const saved = await this.repository.update(entry.id, draft);
            this.state = {type: "idle"};
            return saved;
        } catch (error) {
            this.state = {
                type: "failed",
                field,
                reason: error instanceof KnowledgeNameTakenError ? "nameTaken" : "unknown",
            };
            return null;
        }
    }
}
