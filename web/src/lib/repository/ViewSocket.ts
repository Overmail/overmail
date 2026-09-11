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
        email_sorting: {type: ViewSortingKind; sort_reversed: boolean};
    };
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
        sorting: {
            kind: view.view.email_sorting.type,
            reversed: view.view.email_sorting.sort_reversed,
        },
    };
}
