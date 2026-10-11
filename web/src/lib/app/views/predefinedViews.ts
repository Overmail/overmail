import {SELF_ADDRESSES} from "$lib/repository/ViewSocket";
import type {ViewSettings} from "$lib/app/views/viewSettings";

/**
 * A view the app brings rather than one somebody made.
 *
 * The two a mail client opens on -- what is in front of you, and what you sent -- and one per
 * connected mail account, see [accountView]. They are not stored anywhere -- an account has them
 * the moment it exists, they cannot be renamed or deleted, and their id is a name rather than a
 * uuid, which is also how a url tells them apart from the ones somebody made.
 *
 * Their settings can still be changed for the length of a visit; saving those changes means
 * making a view of one's own out of them.
 */
export type PredefinedView = {
    id: PredefinedViewId;
    /** The i18n key of its name; the name itself has to follow the language. */
    label: string;
    settings: ViewSettings;
};

/**
 * The view of one mail account: a predefined view like the others, except that there is one per
 * connected account rather than one for everybody -- so its id carries the account's.
 */
export type AccountViewId = `account:${string}`;

export type PredefinedViewId = "inbox" | "sent" | AccountViewId;

/** The listing an account opens on when nothing else is asked for. */
export const INBOX_VIEW: PredefinedViewId = "inbox";

/** A colon, which no uuid has: the id of a stored view can never be read as one of these. */
const ACCOUNT_VIEW_PREFIX = "account:";

/** The id of the view that shows the mail account [accountId]. */
export function accountViewId(accountId: string): AccountViewId {
    return `${ACCOUNT_VIEW_PREFIX}${accountId}`;
}

/** The mail account the view [id] is about, or null when it is not an account's view. */
export function accountOfView(id: string | null | undefined): string | null {
    if (!id?.startsWith(ACCOUNT_VIEW_PREFIX)) return null;

    return id.slice(ACCOUNT_VIEW_PREFIX.length) || null;
}

/** Whether [id] names a view the app brings rather than one of the stored ones. */
export function isPredefinedViewId(id: string | null | undefined): id is PredefinedViewId {
    return id === "inbox" || id === "sent" || accountOfView(id) !== null;
}

/**
 * The views the app brings, in the order the sidebar lists them.
 *
 * Built rather than held: a view is settings, and settings are objects somebody is about to edit
 * -- handing out the same one twice would let one screen's changes show up in another.
 */
export function predefinedViews(): PredefinedView[] {
    return [
        {
            id: "inbox",
            label: "views.predefined.inbox",
            settings: {
                // What is left to do: nothing that was put away, newest first.
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
            },
        },
        {
            id: "sent",
            label: "views.predefined.sent",
            settings: {
                groupings: [{kind: "date_smart", reversed: false}],
                filter: {
                    readState: null,
                    // Everything that went out, filed or not; spam is the one state a mail of
                    // one's own is never in.
                    archivedState: ["Unarchive", "Archive"],
                    imapAccountIds: null,
                    // The addresses this account sends from, see [SELF_ADDRESSES] -- which is
                    // what makes "sent" a filter rather than a folder somebody has to have.
                    sentBy: [SELF_ADDRESSES],
                    sentTo: null,
                    hasLabels: null,
                },
                sorting: {kind: "date", reversed: false},
            },
        },
    ];
}

/**
 * The view of the mail account [accountId]: everything that came in through it, spam aside, cut
 * up the way the inbox is.
 *
 * Not in [predefinedViews]: which accounts there are is the server's to say, so the sidebar
 * builds one of these per account it is told about. The label is only what stands in until the
 * account's own name is known -- that is what the view is called.
 */
export function accountView(accountId: string): PredefinedView {
    return {
        id: accountViewId(accountId),
        label: "views.predefined.account",
        settings: {
            groupings: [{kind: "date_smart", reversed: false}],
            filter: {
                readState: null,
                // The account as a whole, not what is left to do in it: filed mails belong to it
                // as much as open ones. Spam is the one thing nobody opens an account to see.
                archivedState: ["Unarchive", "Archive"],
                imapAccountIds: [accountId],
                sentBy: null,
                sentTo: null,
                hasLabels: null,
            },
            sorting: {kind: "date", reversed: false},
        },
    };
}

/** The predefined view [id] names, or the inbox for anything else. */
export function predefinedView(id: string | null | undefined): PredefinedView {
    const accountId = accountOfView(id);
    if (accountId !== null) return accountView(accountId);

    const views = predefinedViews();

    return views.find((view) => view.id === id) ?? views[0];
}
