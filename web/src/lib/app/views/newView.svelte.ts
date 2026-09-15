/**
 * A view that was just created and still wants its name.
 *
 * A new view is created with a generated name ("Neue Ansicht 3") and opened straight away, and
 * naming it is plainly the next thing somebody does -- so the heading over the listing opens its
 * editor by itself. Which of the two places created it does not matter: the plus in the sidebar
 * and the button that keeps changed settings both end up on the same page.
 *
 * A module and not a context, because those two are not in the same tree; the view arrives over
 * the socket a moment after the request, so what is held here is the id and whoever ends up
 * showing that view picks it up.
 */
let requested = $state<string | null>(null);

/** The view whose name should be opened for editing, or null when there is none. */
export function viewToName(): string | null {
    return requested;
}

/** Asks for [id] to be named once it is on screen. Only the last ask stands. */
export function askToName(id: string): void {
    requested = id;
}

/** Takes the request, so one editor opens and not every listing that comes after it. */
export function takeNameRequest(id: string): void {
    if (requested === id) requested = null;
}
