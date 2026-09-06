/**
 * How a pinned column ends: a hard rule, and a shadow falling over what scrolls under it.
 *
 * Only worth drawing while something is actually behind the column -- `*_ON` is added once the
 * table is scrolled sideways, and a `transition-shadow` on the cell fades it in. A column with
 * nothing behind it would be a line for no reason, which is why the table measures rather than
 * always draws it.
 *
 * The same two classes as the mailbox table uses; they live here because the header cell, the
 * body cell and the table that decides between them are three files now.
 */
export const PINNED_LEFT_EDGE = "shadow-none";
export const PINNED_LEFT_EDGE_ON = "shadow-[1px_0_0_0_var(--border),8px_0_12px_-8px_rgb(0_0_0/0.28)]";
