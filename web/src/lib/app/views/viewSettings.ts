import type {View} from "$lib/repository/ViewSocket";

/**
 * What a listing *is*: what it leaves out, how it is cut up, and what orders the mails in it.
 *
 * A view without its name and its place in the sidebar, which is the part a table needs -- and
 * the part that does not have to come from the views api. A table is always given one; whether it
 * is a stored view or one that lives as long as the page is the caller's business.
 */
export type ViewSettings = Pick<View, "groupings" | "filter" | "sorting">;

/**
 * The listing the mailbox itself is: what has not been archived, by date, newest first.
 *
 * The same settings `createView` writes for a new view, minus the filter -- a mailbox is what is
 * left to do, see `IsArchiveFilter`.
 */
export function mailboxView(): ViewSettings {
    return {
        groupings: [{kind: "date_smart", reversed: false}],
        filter: {
            readState: null,
            archivedState: ["Unarchive"],
            imapAccountIds: null,
            sentBy: null,
            sentTo: null,
            hasLabels: null,
        },
        sorting: {kind: "date", reversed: false},
    };
}
