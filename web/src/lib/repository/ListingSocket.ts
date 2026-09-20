import {ReconnectingSocket, type SocketLike} from "$lib/repository/ReconnectingSocket";

const ENDPOINT = "/api/webapp/listing/socket";

/** One group of the listing, as the server counted it. */
export type ListingGroups = {
    /** The levels the groups are cut by, outermost first -- what `by` asked for. */
    groupings: string[];
    groups: {keys: string[]; count: number}[];
};

/** One page of one group: which mail sits at which offset. */
export type ListingPage = {
    /** The keys of the group, comma joined. Empty is the listing as one group. */
    group: string;
    offset: number;
    /** How long the group is, not how much of it this carries. */
    total: number;
    ids: string[];
};

/** A page the client wants kept up to date. */
export type WantedPage = {
    group: string;
    offset: number;
    limit: number;
};

/** What the server sends over this socket. */
type ListingServerMessage =
    | {
          type: "data.listing.groups";
          token: number;
          groupings: string[];
          groups: {keys: string[]; count: number}[];
      }
    | {
          type: "data.listing.page";
          token: number;
          group: string;
          offset: number;
          total: number;
          ids: string[];
      }
    | {type: "data.listing.failed"; error: {status: number; code: string; message: string}};

/**
 * The listing's socket: the shape of the list, and the pages somebody is looking at.
 *
 * Only the index travels here -- which groups there are, how long each is, and which mail sits at
 * which offset. What a row *shows* is subscribed per mail through the email repository, which is
 * a different question with a different lifetime: a mail is what it is whatever listing it turns
 * up in.
 *
 * Nothing is asked for twice. The client says which listing it is on and which pages of it it is
 * looking at, and the server answers those again on its own whenever the mailbox moves -- so a
 * mail arriving is on screen without anybody having to notice and ask.
 *
 * Both of those are what a reconnect loses: the subscription lives on the server's side, so a new
 * connection is told again what is on screen. Which is also how it catches up on whatever
 * happened while there was no connection.
 */
export class ListingSocket {
    private readonly socket: ReconnectingSocket<ListingServerMessage>;

    /** The listing being watched, as a query string; null until a caller said. */
    private query: string | null = null;

    /**
     * What this client calls the current watch, counted up on every change of listing.
     *
     * Every answer carries the token of the watch it is about, and an answer that carries an
     * older one is dropped: the server can have been half way through answering for the listing
     * before when the switch arrived, and filing those mails under the listing now on screen
     * would put the wrong mails in the right-looking rows.
     */
    private token = 0;

    /** The pages being watched, as they last went out. */
    private pages: WantedPage[] = [];

    constructor(config: {
        onGroups: (groups: ListingGroups) => void;
        onPage: (page: ListingPage) => void;
        /** The server could not read what was asked for; the message is for a log. */
        onFailed: (message: string) => void;
        /** Defaults to a real browser socket. */
        open?: (url: string) => SocketLike;
        /** Overridden in tests, which have no second to wait. */
        reconnectDelays?: number[];
    }) {
        this.socket = new ReconnectingSocket<ListingServerMessage>({
            url: ENDPOINT,
            open: config.open,
            reconnectDelays: config.reconnectDelays,
            onOpen: () => {
                if (this.query === null) return;

                // Both of them, and the listing first: a page is an offset into a listing, so
                // the server has to be told which one before it can answer for one.
                this.announce();
            },
            onMessage: (message) => {
                switch (message.type) {
                    case "data.listing.groups":
                        if (message.token !== this.token) break;
                        config.onGroups({groupings: message.groupings, groups: message.groups});
                        break;
                    case "data.listing.page":
                        if (message.token !== this.token) break;
                        config.onPage({
                            group: message.group,
                            offset: message.offset,
                            total: message.total,
                            ids: message.ids,
                        });
                        break;
                    case "data.listing.failed":
                        config.onFailed(message.error.message);
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

    /**
     * Switches which listing this is about: the query string the listing endpoints take, which is
     * the one spelling of a filter there is.
     *
     * The pages go with it -- they were offsets into the listing before, and mean other mails in
     * this one. Whoever scrolls says what it wants next.
     */
    watch(query: string) {
        if (this.query === query) return;

        this.query = query;
        this.pages = [];
        this.socket.start();
        this.announce();
    }

    /**
     * Says again what this socket is about, under a token of its own -- what a retry after a
     * failure does. Whatever the old watch still answers is dropped on arrival.
     */
    rewatch() {
        if (this.query === null) return;

        this.socket.start();
        this.announce();
    }

    /**
     * The current watch, and the pages under it, out to a connection that is up.
     *
     * Nothing goes out while the handshake is still running: what a caller wants is re-announced
     * from [ReconnectingSocket]'s own onOpen, so a message sent into a connecting socket would be
     * the same one twice under two tokens -- and the browser refuses it anyway.
     */
    private announce() {
        if (!this.socket.isOpen) return;

        this.token++;
        this.socket.send({type: "watch.listing", query: this.query, token: this.token});
        if (this.pages.length > 0) this.socket.send({type: "watch.pages", pages: this.pages});
    }

    /**
     * The pages being looked at, which is what the server keeps up to date.
     *
     * The whole set every time rather than one on and one off: it is a window, and what left it
     * is as much part of the message as what entered it. A set that is already being watched is
     * not sent again -- this is called on every scroll frame.
     */
    watchPages(pages: WantedPage[]) {
        if (this.query === null) return;
        if (samePages(this.pages, pages)) return;

        this.pages = pages;
        this.socket.send({type: "watch.pages", pages});
    }
}

/** Whether two sets of pages ask for the same thing, in the same order. */
function samePages(a: WantedPage[], b: WantedPage[]): boolean {
    if (a.length !== b.length) return false;

    return a.every((page, index) => {
        const other = b[index];
        return page.group === other.group && page.offset === other.offset && page.limit === other.limit;
    });
}
