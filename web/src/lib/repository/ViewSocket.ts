import {ReconnectingSocket, type SocketLike} from "$lib/repository/ReconnectingSocket";

const ENDPOINT = "/api/webapp/views/socket";

/** What a listing can be cut by. The server's `ViewSettings.Grouping` names. */
export type ViewGroupingKind =
    | "date_smart"
    | "year"
    | "month"
    | "day"
    | "sender"
    | "imap_account"
    | "read"
    | "archived";

/** What an archived state can be, the server's `EmailArchiveAction` names. */
export type ViewArchivedState = "Archive" | "Unarchive" | "Spam";

/** What the mails inside the deepest group are ordered by. */
export type ViewSortingKind = "date" | "sender" | "subject";

/** One category a view groups by. `reversed` turns that category's order around. */
export type ViewGrouping = {
    kind: ViewGroupingKind;
    reversed: boolean;
};

export type ViewSorting = {
    kind: ViewSortingKind;
    reversed: boolean;
};

/**
 * The one value in a correspondent filter that is not an id: the addresses this account sends
 * from, which is what makes a "sent" listing a filter rather than a folder.
 *
 * Resolved by the server against the logins of the mail accounts -- of all of them, or of the
 * ones `imapAccountIds` names. A name rather than the ids of the moment, because an address book
 * entry for one's own address only appears once a mail of it has been imported.
 */
export const SELF_ADDRESSES = "self";

/**
 * What a view leaves out, before anything is grouped or sorted.
 *
 * Null is no restriction on that attribute, which is not the same as an empty list: a list says
 * which values pass, so an empty one lets nothing through. What is set is read together with the
 * rest, not as alternatives. The ids are the server's -- inboxes, mail addresses, labels.
 */
export type ViewFilter = {
    /** True only read, false only unread, null both. */
    readState: boolean | null;
    archivedState: ViewArchivedState[] | null;
    imapAccountIds: string[] | null;
    /** Address book ids, and [SELF_ADDRESSES] for this account's own addresses. */
    sentBy: string[] | null;
    sentTo: string[] | null;
    hasLabels: string[] | null;
};

/** One of the user's views, as the socket reports it. */
export type View = {
    id: string;
    name: string;
    /**
     * Its place in the list, as a fractional index. The list arrives in this order already; the
     * key is what a client needs to say where a view it moves should end up.
     */
    sortKey: string;
    /** The categories it groups by, outermost first. Empty is an ungrouped listing. */
    groupings: ViewGrouping[];
    filter: ViewFilter;
    sorting: ViewSorting;
};

/**
 * One view on the wire. The socket and `POST /api/users/me/views/new` send the same shape, so
 * both are read through [parseView].
 */
export type ViewPayload = {
    id: string;
    name: string;
    sort_key: string;
    view: {
        groupings: {type: ViewGroupingKind; sort_reversed: boolean}[];
        /** Absent says the same as a filter of nothing but nulls, see [parseViewFilter]. */
        filter?: ViewFilterPayload | null;
        email_sorting: {type: ViewSortingKind; sort_reversed: boolean};
    };
};

/** One filter on the wire. Every key is optional: what is not there restricts nothing. */
export type ViewFilterPayload = {
    read_state?: boolean | null;
    archived_state?: ViewArchivedState[] | null;
    imap_account_ids?: string[] | null;
    sent_by?: string[] | null;
    sent_to?: string[] | null;
    has_labels?: string[] | null;
};

/** What the server sends over this socket. */
type ViewServerMessage = {
    type: "data.views";
    views: ViewPayload[];
};

/**
 * The views socket: every view the user has, in sidebar order.
 *
 * The whole list arrives at once, on connect and again after every change -- including changes
 * another tab made. There is nothing to subscribe to and nothing to re-request after a reconnect:
 * the connection itself is the subscription, and a fresh one starts by sending the list.
 */
export class ViewSocket {
    private readonly socket: ReconnectingSocket<ViewServerMessage>;

    constructor(config: {
        onViews: (views: View[]) => void;
        /** Defaults to a real browser socket. */
        open?: (url: string) => SocketLike;
        /** Overridden in tests, which have no second to wait. */
        reconnectDelays?: number[];
    }) {
        this.socket = new ReconnectingSocket<ViewServerMessage>({
            url: ENDPOINT,
            open: config.open,
            reconnectDelays: config.reconnectDelays,
            onMessage: (message) => {
                switch (message.type) {
                    case "data.views":
                        config.onViews(message.views.map(parseView));
                        break;
                }
            },
        });
    }

    start() {
        this.socket.start();
    }

    stop() {
        this.socket.stop();
    }
}

/** A view as it comes off the wire, in the shape the app uses. */
export function parseView(view: ViewPayload): View {
    return {
        id: view.id,
        name: view.name,
        sortKey: view.sort_key,
        groupings: view.view.groupings.map((grouping) => ({
            kind: grouping.type,
            reversed: grouping.sort_reversed,
        })),
        filter: parseViewFilter(view.view.filter),
        sorting: {
            kind: view.view.email_sorting.type,
            reversed: view.view.email_sorting.sort_reversed,
        },
    };
}

/**
 * The filter of a view, as the app holds it.
 *
 * A filter that is not there at all reads as one that restricts nothing -- that is what a view
 * stored before filters existed says, and what a server that leaves its defaults out of the
 * answer says as well. Built fresh every time: the filter belongs to the one view it came with.
 */
export function parseViewFilter(filter: ViewFilterPayload | null | undefined): ViewFilter {
    return {
        readState: filter?.read_state ?? null,
        archivedState: filter?.archived_state ?? null,
        imapAccountIds: filter?.imap_account_ids ?? null,
        sentBy: filter?.sent_by ?? null,
        sentTo: filter?.sent_to ?? null,
        hasLabels: filter?.has_labels ?? null,
    };
}
