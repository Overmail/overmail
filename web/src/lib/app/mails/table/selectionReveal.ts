/**
 * When a checkbox of the table is there, and what it takes the place of.
 *
 * Both sides of the same swap, written once because a row and the header over it do it together:
 * every row of the table -- header or mail -- is `group/mail-row`, so the cursor arriving on one
 * brings its box out with CSS alone and nothing re-renders while somebody runs down the list.
 *
 * A box that is ticked stays out, which is the one part that is not hover: the rows that are
 * picked show their box and their colour, and the rest of the list keeps its faces. Both
 * components add [SHOWN] and [HIDDEN] for that, and it beats the rules below by coming last --
 * `cn` is tailwind-merge, so the later of two utilities of the same kind is the one that lands.
 *
 * Out of sight and out of reach are the same state: a box only takes a click while it can be
 * seen, or what looks like an avatar would quietly be a checkbox. On a screen without a cursor
 * `hover:` never matches, so an untouched row keeps its face and a tap opens the mail as it
 * always did.
 */

/** The box: nowhere until the row is hovered or it has the focus. */
export const CHECKBOX_REVEAL =
    "pointer-events-none scale-75 opacity-0 transition-all duration-150 ease-out after:-inset-2 " +
    "group-hover/mail-row:pointer-events-auto group-hover/mail-row:scale-100 group-hover/mail-row:opacity-100 " +
    "focus-visible:pointer-events-auto focus-visible:scale-100 focus-visible:opacity-100";

/** What the box replaces, on the same square: the mail's avatar, gone while the box is there. */
export const AVATAR_REVEAL =
    "transition-all duration-150 ease-out " +
    "group-hover/mail-row:scale-75 group-hover/mail-row:opacity-0";

/** Out for good, whatever the cursor is doing. */
export const SHOWN = "pointer-events-auto scale-100 opacity-100";
export const HIDDEN = "scale-75 opacity-0";
