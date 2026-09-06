/**
 * Which mails of the table are ticked -- and with that, whether the table is in selection mode at
 * all.
 *
 * Ids and not rows: the list is windowed, so a ticked mail scrolls out of the DOM and has to come
 * back ticked, and a position says nothing once new mail arrives and moves every row down.
 *
 * The table owns one of these and puts it into the context below, because a cell is handed the
 * mail and nothing else -- see columns.ts, where the table's cells are declared without a table
 * to pass anything down from.
 */
import {getContext, setContext} from "svelte";
import {SvelteSet} from "svelte/reactivity";

const key = Symbol("mail-selection");

export class MailSelection {
    /**
     * A reactive set rather than a reactive array: `has` is read once per row on screen, and this
     * way ticking one mail wakes that mail's row instead of all of them.
     */
    readonly #ids = new SvelteSet<string>();

    /** The ticked mails, in no order worth relying on. */
    get ids(): string[] {
        return [...this.#ids];
    }

    get count(): number {
        return this.#ids.size;
    }

    /**
     * Whether anything is ticked, which is all "selection mode" means here: there is no switch to
     * turn on. The first tick puts the table into it and taking back the last one ends it -- and
     * a row shows its box because *it* is ticked, not because the table is in the mode, so this
     * is for what is about the selection as a whole (the way out of it, what acts on it).
     */
    get active(): boolean {
        return this.#ids.size > 0;
    }

    has(id: string): boolean {
        return this.#ids.has(id);
    }

    set(id: string, selected: boolean) {
        if (selected) this.#ids.add(id);
        else this.#ids.delete(id);
    }

    toggle(id: string) {
        this.set(id, !this.has(id));
    }

    /** How many of [ids] are ticked -- what tells a stretch's box apart from its third state. */
    countOf(ids: string[]): number {
        let count = 0;
        for (const id of ids) {
            if (this.#ids.has(id)) count++;
        }

        return count;
    }

    /** A whole stretch in one go, which is what the box over a group does. */
    setAll(ids: string[], selected: boolean) {
        for (const id of ids) this.set(id, selected);
    }

    clear() {
        // Guarded: this is called from an effect, and a `clear` that writes when there is nothing
        // to clear would wake whatever reads the set for no reason.
        if (this.#ids.size > 0) this.#ids.clear();
    }
}

export function setMailSelection(selection: MailSelection) {
    setContext(key, selection);
}

/** Null wherever a cell is rendered outside the table, e.g. in a preview card or a harness. */
export function getMailSelection(): MailSelection | null {
    return getContext<MailSelection | undefined>(key) ?? null;
}
