const ENDPOINT = "/api/webapp/home/socket";

/**
 * How long to wait before trying again, per attempt in a row; the last one repeats. A dropped
 * connection is usually the server restarting or a laptop waking up, so the first retry is quick
 * and a lasting outage is not hammered.
 */
const RECONNECT_DELAYS = [1_000, 2_000, 5_000, 10_000, 30_000];

/** What the server sends over this socket. */
type HomeServerMessage = {
    type: "data.mailbox.count";
    unarchived: number;
};

/**
 * The part of `WebSocket` this uses. A test hands in its own; nothing else has a reason to.
 */
export type SocketLike = {
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
    private readonly open: (url: string) => SocketLike;

    private readonly delays: number[];

    constructor(config: {
        onMailboxCount: (count: number) => void;
        /** Defaults to a real browser socket. */
        open?: (url: string) => SocketLike;
        /** Overridden in tests, which have no second to wait. */
        reconnectDelays?: number[];
    }) {
        this.onMailboxCount = config.onMailboxCount;
        this.open = config.open ?? ((url) => new WebSocket(url) as unknown as SocketLike);
        this.delays = config.reconnectDelays ?? RECONNECT_DELAYS;
    }

    /** Connects, and keeps it that way until [stop]. Calling it again while up does nothing. */
    start() {
        this.isStopped = false;
        if (this.socket !== null) return;
        this.connect();
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

    private connect() {
        const socket = this.open(ENDPOINT);
        this.socket = socket;

        socket.onopen = () => {
            // Reached the server, so a later drop starts counting from the short delay again.
            this.failures = 0;
        };

        socket.onmessage = (event) => {
            const message = JSON.parse(event.data) as HomeServerMessage;
            switch (message.type) {
                case "data.mailbox.count":
                    this.onMailboxCount(message.unarchived);
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
