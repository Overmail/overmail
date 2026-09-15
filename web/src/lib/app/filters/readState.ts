import type {ReadState} from "$lib/app/filters/IsUnreadFilter.svelte";

/**
 * What `ViewFilter.readState` is for the states a chip has picked: true for read alone, false for
 * unread alone, null for both or neither.
 *
 * Both and neither come out the same on purpose: every mail is one or the other, so naming both
 * restricts nothing. The chip keeps the two apart because they are different things to say; a
 * listing cannot tell them apart.
 */
export function readStateOf(selected: ReadState[]): boolean | null {
    const read = selected.includes("read");
    const unread = selected.includes("unread");
    if (read === unread) return null;

    return read;
}
