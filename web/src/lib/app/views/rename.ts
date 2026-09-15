/**
 * What a rename submits: the typed name, trimmed, or null when the view keeps the one it has.
 *
 * One rule for both ways of ending up with the old name -- an empty box and a name that was only
 * retyped -- because neither is something to send: a blank name is the user saying "never mind"
 * rather than asking for a nameless view, and the server refuses it anyway.
 */
export function renamedTo(current: string, typed: string): string | null {
    const name = typed.trim();
    if (name.length === 0) return null;
    if (name === current) return null;

    return name;
}
