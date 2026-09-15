import {SELF_ADDRESSES} from "$lib/repository/ViewSocket";
import type {ViewSettings} from "$lib/app/views/viewSettings";

/**
 * A view the app brings rather than one somebody made.
 *
 * Two of them, and both are what a mail client opens on: what is in front of you, and what you
 * sent. They are not stored anywhere -- an account has them the moment it exists, they cannot be
 * renamed or deleted, and their id is a name rather than a uuid, which is also how a url tells
 * them apart from the ones in the sidebar.
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

export type PredefinedViewId = "inbox" | "sent";

/** The listing an account opens on when nothing else is asked for. */
export const INBOX_VIEW: PredefinedViewId = "inbox";

/** Whether [id] names a view the app brings rather than one of the stored ones. */
export function isPredefinedViewId(id: string | null | undefined): id is PredefinedViewId {
    return id === "inbox" || id === "sent";
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

/** The predefined view [id] names, or the inbox for anything else. */
export function predefinedView(id: string | null | undefined): PredefinedView {
    const views = predefinedViews();

    return views.find((view) => view.id === id) ?? views[0];
}
