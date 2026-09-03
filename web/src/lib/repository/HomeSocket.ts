import {ReconnectingSocket, type SocketLike} from "$lib/repository/ReconnectingSocket";

const ENDPOINT = "/api/webapp/home/socket";

/** One year of the heatmap, as it comes off the socket. */
export type MailGraph = {
    year: number;
    /** Every year the mailbox has mail in, oldest first. [year] need not be one of them. */
    availableYears: number[];
    /** `yyyy-mm-dd` to the mails that arrived that day; a quiet day is absent, not zero. */
    days: ReadonlyMap<string, number>;
};

/** What the server sends over this socket. */
type HomeServerMessage =
    | {type: "data.mailbox.count"; unarchived: number}
    | {
          type: "data.mail_graph";
          year: number;
          available_years: number[];
          days: Record<string, number>;
      };

/**
 * The home screen's socket: the size of the mailbox, and the heatmap year by year.
 *
 * A count is only correct while the socket is up, so after a reconnect the server sends the
 * current one again -- the repository above simply takes what arrives and does not have to know
 * that anything was interrupted.
 */
export class HomeSocket {
    private readonly socket: ReconnectingSocket<HomeServerMessage>;

    /**
     * Years the client wants, the current one aside -- the server sends that one on its own.
     *
     * Kept here rather than in the repository above because they are what a reconnect loses: the
     * subscription lives on the server's side of the socket, so a new connection has to be told
     * again what is on screen.
     */
    private readonly years = new Set<number>();

    constructor(config: {
        onMailboxCount: (count: number) => void;
        onMailGraph: (graph: MailGraph) => void;
        /** Defaults to a real browser socket. */
        open?: (url: string) => SocketLike;
        /** Overridden in tests, which have no second to wait. */
        reconnectDelays?: number[];
    }) {
        this.socket = new ReconnectingSocket<HomeServerMessage>({
            url: ENDPOINT,
            open: config.open,
            reconnectDelays: config.reconnectDelays,
            onOpen: () => this.years.forEach((year) => this.request(year)),
            onMessage: (message) => {
                switch (message.type) {
                    case "data.mailbox.count":
                        config.onMailboxCount(message.unarchived);
                        break;
                    case "data.mail_graph":
                        config.onMailGraph({
                            year: message.year,
                            availableYears: message.available_years,
                            days: new Map(Object.entries(message.days)),
                        });
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
     * Asks for a year of the heatmap and to be kept up to date on it, now and after every
     * reconnect. The current year needs no request.
     */
    requestYear(year: number) {
        if (this.years.has(year)) return;
        this.years.add(year);
        this.request(year);
    }

    private request(year: number) {
        this.socket.send({type: "request.mail_graph", year});
    }
}
