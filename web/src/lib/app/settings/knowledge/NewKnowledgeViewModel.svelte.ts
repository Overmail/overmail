import {
    KnowledgeNameTakenError,
    type KnowledgeEntry,
    type KnowledgeRepository,
} from "$lib/repository/KnowledgeRepository";

/**
 * How many keywords an entry keeps; see `Knowledge.MAX_KEYWORDS` on the server, which cuts the
 * rest off silently. The form stops before that, so nobody types a keyword that is dropped.
 */
export const MAX_KEYWORDS = 24;

/** Where saving stands. [nameTaken] is the one failure a field can point at. */
export type SaveState = {type: "idle"} | {type: "saving"} | {type: "nameTaken"} | {type: "failed"};

/**
 * The "add knowledge" form.
 *
 * A view model rather than state in the dialog, like the inbox form: the keyword field has rules
 * of its own (what counts as a keyword, what a duplicate is, when the list is full) and those are
 * worth testing without a browser.
 *
 * The keywords are normalized here the way the server stores them -- lowercase, trimmed, inner
 * whitespace as one space. Not to save the server the work, which it does anyway, but so the chip
 * the user sees is the keyword that ends up in the row; anything else and "Rheinenergie" would be
 * added twice by anyone typing it in two capitalizations.
 */
export class NewKnowledgeViewModel {
    name = $state("");
    description = $state("");
    /** `yyyy-mm-dd`, or empty for the entries that are not about a day -- which is most of them. */
    relevantOn = $state("");
    /** The chips, in the order they were added. */
    keywords: string[] = $state([]);
    /** What is in the keyword field and not a chip yet. */
    keywordDraft = $state("");

    saveState: SaveState = $state({type: "idle"});

    constructor(private readonly repository: KnowledgeRepository) {}

    get saving(): boolean {
        return this.saveState.type === "saving";
    }

    /** An entry is its name and what is known; the rest is optional. */
    get canSubmit(): boolean {
        return !this.saving && this.name.trim().length > 0 && this.description.trim().length > 0;
    }

    get keywordsFull(): boolean {
        return this.keywords.length >= MAX_KEYWORDS;
    }

    /**
     * Typing in the name is the answer to "that name is taken", so the message goes with it --
     * leaving it up would have the field contradict the error under it.
     */
    setName(name: string) {
        this.name = name;
        if (this.saveState.type === "nameTaken") this.saveState = {type: "idle"};
    }

    /**
     * Turns what is in the keyword field into chips.
     *
     * Commas separate, so pasting a list works and so does typing one; a keyword that is already
     * there is not added twice. Returns what was left over -- nothing, unless the list is full.
     */
    commitKeywords(raw: string = this.keywordDraft) {
        const rest: string[] = [];

        for (const candidate of raw.split(",")) {
            const keyword = normalizeKeyword(candidate);
            if (keyword.length === 0) continue;
            if (this.keywords.includes(keyword)) continue;
            if (this.keywordsFull) {
                rest.push(keyword);
                continue;
            }
            this.keywords = [...this.keywords, keyword];
        }

        // What did not fit stays in the field rather than disappearing: the user typed it, and
        // the counter next to the field says why it is still sitting there.
        this.keywordDraft = rest.join(", ");
    }

    removeKeyword(keyword: string) {
        this.keywords = this.keywords.filter((existing) => existing !== keyword);
    }

    /** Backspace in an empty field: takes the last chip back into it, so it can be corrected. */
    editLastKeyword() {
        if (this.keywordDraft.length > 0 || this.keywords.length === 0) return;
        this.keywordDraft = this.keywords[this.keywords.length - 1];
        this.keywords = this.keywords.slice(0, -1);
    }

    /**
     * Writes the entry, or leaves the form standing with the reason on it.
     *
     * A keyword still in the field is committed first: it was typed for this entry, and losing it
     * to a missing Enter is not something the user can see happening.
     */
    async submit(): Promise<KnowledgeEntry | null> {
        this.commitKeywords();
        if (!this.canSubmit) return null;

        this.saveState = {type: "saving"};
        try {
            const entry = await this.repository.create({
                name: this.name.trim(),
                description: this.description.trim(),
                keywords: this.keywords,
                relevantOn: this.relevantOn.length > 0 ? this.relevantOn : null,
            });
            this.saveState = {type: "idle"};
            return entry;
        } catch (error) {
            this.saveState = error instanceof KnowledgeNameTakenError ? {type: "nameTaken"} : {type: "failed"};
            return null;
        }
    }

    /** Back to an empty form. The dialog stays mounted, so this is what makes the next one new. */
    reset() {
        this.name = "";
        this.description = "";
        this.relevantOn = "";
        this.keywords = [];
        this.keywordDraft = "";
        this.saveState = {type: "idle"};
    }
}

/** A keyword as the server stores it; see `Knowledge.normalizeKeyword`. */
function normalizeKeyword(keyword: string): string {
    return keyword.trim().toLowerCase().replace(/\s+/g, " ");
}
