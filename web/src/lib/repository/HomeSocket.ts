const ENDPOINT = "/api/webapp/home/socket";

/**
 * How long to wait before trying again, per attempt in a row; the last one repeats. A dropped
 * connection is usually the server restarting or a laptop waking up, so the first retry is quick
 * and a lasting outage is not hammered.
 */
const RECONNECT_DELAYS = [1_000, 2_000, 5_000, 10_000, 30_000];

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
 * The part of `WebSocket` this uses. A test hands in its own; nothing else has a reason to.
 */
export type SocketLike = {
    send(data: string): void;
    close(): void;
    onopen: (() => void) | null;
    onclose: ((event: {wasClean: boolean}) => void) | null;
    onmessage: ((event: {data: string}) => void) | null;
};

/**
 * The home screen's socket: connects, reports what comes in, and gets itself back up when the
 * connection drops.
 *
 * Reconnecting is the whole reason this is a class. A count is only correct while the socket is
 * up, so after a reconnect the server sends the current one again -- the repository above simply
 * takes what arrives and does not have to know that anything was interrupted.
 */
export class HomeSocket {
    private socket: SocketLike | null = null;
    private retry: ReturnType<typeof setTimeout> | null = null;

    /** How many closes in a row without a connection in between; picks the delay. */
    private failures = 0;

    /** Set by [stop], so a close it caused is not answered with a reconnect. */
    private isStopped = true;

    private readonly onMailboxCount: (count: number) => void;
    private readonly onMailGraph: (graph: MailGraph) => void;
    private readonly open: (url: string) => SocketLike;

    private readonly delays: number[];

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
        this.onMailboxCount = config.onMailboxCount;
        this.onMailGraph = config.onMailGraph;
        this.open = config.open ?? ((url) => new WebSocket(url) as unknown as SocketLike);
        this.delays = config.reconnectDelays ?? RECONNECT_DELAYS;
    }

    /** Connects, and keeps it that way until [stop]. Calling it again while up does nothing. */
    start() {
        this.isStopped = false;
        if (this.socket !== null) return;
        this.connect();
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

    /** Closes for good: no reconnect follows this one. */
    stop() {
        this.isStopped = true;
        if (this.retry !== null) {
            clearTimeout(this.retry);
            this.retry = null;
        }
        const socket = this.socket;
        this.socket = null;
        this.failures = 0;
        socket?.close();
    }

    /** Dropped while there is no connection: [onopen] asks for every year again anyway. */
    private request(year: number) {
        this.socket?.send(JSON.stringify({type: "request.mail_graph", year}));
    }

    private connect() {
        const socket = this.open(ENDPOINT);
        this.socket = socket;

        socket.onopen = () => {
            // Reached the server, so a later drop starts counting from the short delay again.
            this.failures = 0;
            // A fresh connection knows nothing about what is on screen, so it is told again.
            this.years.forEach((year) => this.request(year));
        };

        socket.onmessage = (event) => {
            const message = JSON.parse(event.data) as HomeServerMessage;
            switch (message.type) {
                case "data.mailbox.count":
                    this.onMailboxCount(message.unarchived);
                    break;
                case "data.mail_graph":
                    this.onMailGraph({
                        year: message.year,
                        availableYears: message.available_years,
                        days: new Map(Object.entries(message.days)),
                    });
                    break;
            }
        };

        socket.onclose = () => {
            if (this.socket !== socket) return; // an old socket reporting after a stop
            this.socket = null;
            if (this.isStopped) return;

            const delay = this.delays[Math.min(this.failures, this.delays.length - 1)];
            this.failures++;
            this.retry = setTimeout(() => {
                this.retry = null;
                if (!this.isStopped) this.connect();
            }, delay);
        };
    }
}
