import type { Mail, MailPage, MailTag } from '$lib/repository/MailRepository';

/** Where the socket lives. Under `/api`, so the proxy sends it to Ktor like everything else. */
const PATH = '/api/webapp/my-stack/';

/**
 * How long a command may go unanswered before the socket counts as dead.
 *
 * A socket that is open but silent is the failure nothing else catches: no close event, no error,
 * the answer simply never comes. Without this the screen would sit there loading forever.
 */
const ANSWER_TIMEOUT = 10_000;

/**
 * How long to wait before each reconnect, and how many there are. Short first, because most drops
 * are a proxy timeout or a moment of no network and the next attempt just works; then longer, so a
 * server that is actually down is not hammered. Once they run out, whatever was waiting is failed
 * and the screen offers the reader the retry button.
 */
const RECONNECT_DELAYS = [250, 1_000, 3_000, 6_000];

/** What the stack asks the server for. A command id is added on the way out. */
type StackCommand =
	| { type: 'load_mails'; limit: number; before?: string }
	| { type: 'set_tags'; mail: string; tags: string[] }
	| { type: 'set_archived'; mail: string; archived: boolean }
	| { type: 'set_spam'; mail: string; spam: boolean; filter?: string };

/**
 * What the server sends down the socket. `reply_to` names the command it answers; an event without
 * one is the server keeping the screen in sync on its own.
 */
type StackEvent =
	| {
			type: 'mails';
			reply_to?: number | null;
			mails: Mail[];
			total: number;
			before?: string | null;
	  }
	| { type: 'mail_tags'; reply_to?: number | null; mail: string; tags: MailTag[] }
	| { type: 'mail_archived'; reply_to?: number | null; mail: string; archived: boolean }
	| { type: 'mail_spam'; reply_to?: number | null; mail: string; spam: boolean }
	| { type: 'tags'; reply_to?: number | null; tags: MailTag[] }
	| { type: 'error'; reply_to?: number | null; command?: string | null; message: string };

/** A command that has been asked for and not answered yet. */
type Pending = {
	id: number;
	frame: string;
	/** Whether it went out on the socket that is open now; false again after a reconnect. */
	sent: boolean;
	resolve: (event: StackEvent) => void;
	reject: (cause: Error) => void;
};

/** Builds the socket. Injectable so the wiring can be driven without a server. */
export type SocketFactory = (url: string) => WebSocket;

/**
 * The stack screen's channel to the server.
 *
 * A socket rather than a request per ask, because the screen's traffic belongs on a connection that
 * is already open: the mails, the tags the reader files them under, the archive they put them in,
 * and the tag list the autocomplete runs on, which the server sends along whenever it changes. Bodies are the one
 * exception and stay on `MailRepository.getContent` -- they are big, the browser caches them, and
 * nothing about them is live.
 *
 * Opened on the first ask rather than in the constructor, so this can be built while the page is
 * rendered on the server. A socket that drops with nothing outstanding is simply forgotten and the
 * next ask opens a new one; a drop with commands in flight reconnects on its own and sends them
 * again, so a proxy timeout or a lost minute of network never reaches the reader. The caller only
 * hears about it once [RECONNECT_DELAYS] is used up: then the command is rejected, and asking again
 * is the screen's retry button.
 *
 * Resending is safe because every command says what should be true rather than what to change --
 * `set_tags` carries the whole set of tags for a mail, `set_archived` and `set_spam` the state it
 * should be in,
 * so running either twice leaves the same thing behind.
 *
 * Reconnecting is driven by what is outstanding, not by a keepalive of its own: nothing here holds
 * a connection open for a screen that is not asking for anything.
 */
export class StackSocket {
	readonly #createSocket: SocketFactory;

	#socket: WebSocket | null = null;
	/** Commands asked for and not answered yet, oldest first; matched by their id. */
	#pending: Pending[] = [];
	/** Numbers the commands are told apart by. */
	#lastId = 0;
	/** Which reconnect is next. Back to zero as soon as an answer arrives. */
	#attempt = 0;
	#reconnecting: ReturnType<typeof setTimeout> | null = null;
	#timeout: ReturnType<typeof setTimeout> | null = null;
	/** Set by [close]: the screen is gone, so nothing reopens the socket. */
	#closed = false;
	#tagsListener: ((tags: MailTag[]) => void) | null = null;

	constructor(createSocket: SocketFactory = (url) => new WebSocket(url)) {
		this.#createSocket = createSocket;
	}

	/**
	 * Watches the caller's tag list, which the server sends when the socket opens and again on every
	 * change. One listener; the store is it.
	 */
	onTags(listener: (tags: MailTag[]) => void): void {
		this.#tagsListener = listener;
	}

	/** The next mails, newest first, ending before `before`. */
	async requestMails(query: { limit: number; before?: string }): Promise<MailPage> {
		const event = await this.#request({ type: 'load_mails', ...query });
		if (event.type !== 'mails') throw new Error(`Asked for mails, got ${event.type}`);

		return { mails: event.mails, total: event.total };
	}

	/**
	 * Files a mail under exactly these tags, by name, creating the ones that do not exist yet.
	 * Answers with the tags the mail ends up carrying, in the spelling the server stored.
	 */
	async setTags(mail: string, tags: string[]): Promise<MailTag[]> {
		const event = await this.#request({ type: 'set_tags', mail, tags });
		if (event.type !== 'mail_tags') throw new Error(`Filed tags, got ${event.type}`);

		return event.tags;
	}

	/**
	 * Puts a mail into the archive, or takes it back out -- which is what taking a decision back on
	 * the stack means. Resolves once the change is stored.
	 */
	async setArchived(mail: string, archived: boolean): Promise<void> {
		const event = await this.#request({ type: 'set_archived', mail, archived });
		if (event.type !== 'mail_archived') throw new Error(`Archived a mail, got ${event.type}`);
	}

	/**
	 * Flags a mail as spam, or takes it back out. Resolves once the change is stored.
	 *
	 * `filter` is the filter that caught it; the stack leaves it out, since a reader flagging a
	 * mail themselves is what this is for. Until a mail is flagged the server keeps handing it
	 * back, so this is what takes it out of the stack for good.
	 */
	async setSpam(mail: string, spam: boolean, filter?: string): Promise<void> {
		const event = await this.#request({ type: 'set_spam', mail, spam, filter });
		if (event.type !== 'mail_spam') throw new Error(`Flagged a mail, got ${event.type}`);
	}

	/** Hangs up for good and fails whatever was still on its way. Leaving the screen calls this. */
	close(): void {
		this.#closed = true;
		this.#clearTimers();

		const socket = this.#socket;
		this.#socket = null;
		socket?.close();

		this.#failPending(new Error('The stack socket was closed'));
	}

	#request(command: StackCommand): Promise<StackEvent> {
		if (this.#closed) return Promise.reject(new Error('The stack socket is closed'));

		const id = (this.#lastId += 1);
		const frame = JSON.stringify({ ...command, id });

		return new Promise<StackEvent>((resolve, reject) => {
			this.#pending.push({ id, frame, sent: false, resolve, reject });
			this.#flush();
		});
	}

	/** Sends everything that has not gone out yet, opening the socket if it has to. */
	#flush(): void {
		if (this.#closed || this.#pending.length === 0) return;

		const socket = this.#open();
		// Still shaking hands: the open event runs this again, and the frames wait until then.
		if (socket.readyState !== WebSocket.OPEN) return;

		for (const waiting of this.#pending) {
			if (waiting.sent) continue;

			socket.send(waiting.frame);
			waiting.sent = true;
		}

		this.#armTimeout();
	}

	#open(): WebSocket {
		if (this.#socket) return this.#socket;

		const socket = this.#createSocket(socketUrl());
		this.#socket = socket;

		socket.onopen = () => this.#flush();
		socket.onmessage = (message) => this.#receive(String(message.data));
		// A socket that errors is a socket that is going: closing it here puts both cases through
		// the one path below.
		socket.onerror = () => socket.close();
		socket.onclose = () => {
			if (this.#socket !== socket) return;

			this.#drop();
			// Only reconnects for work that is waiting; an idle drop is picked up by the next ask.
			if (this.#pending.length > 0) this.#scheduleReconnect();
		};

		return socket;
	}

	#receive(data: string): void {
		let event: StackEvent;
		try {
			event = JSON.parse(data) as StackEvent;
		} catch {
			return;
		}

		// Anything at all means the connection works, an `error` event included: that one is the
		// server turning a command down, not the socket failing.
		this.#attempt = 0;

		if (event.reply_to === undefined || event.reply_to === null) {
			// Sent on the server's own initiative. An error without a command is one this client
			// cannot act on either -- the answer it was waiting for then runs into the timeout.
			if (event.type === 'tags') this.#tagsListener?.(event.tags);
			return;
		}

		const at = this.#pending.findIndex((waiting) => waiting.id === event.reply_to);
		if (at < 0) return;

		const [waiting] = this.#pending.splice(at, 1);
		this.#clearTimeout();
		this.#armTimeout();

		if (event.type === 'error') waiting.reject(new Error(event.message));
		else waiting.resolve(event);
	}

	/** Puts the socket aside so the next flush builds a new one, and marks its frames unsent. */
	#drop(): void {
		this.#socket = null;
		this.#clearTimeout();
		for (const waiting of this.#pending) waiting.sent = false;
	}

	#scheduleReconnect(): void {
		if (this.#closed || this.#reconnecting) return;

		const delay = RECONNECT_DELAYS[this.#attempt];
		if (delay === undefined) {
			// Out of attempts. The counter goes back to zero, so the retry button starts over.
			this.#attempt = 0;
			this.#failPending(new Error('The stack socket could not be reached'));
			return;
		}

		this.#attempt += 1;
		this.#reconnecting = setTimeout(() => {
			this.#reconnecting = null;
			this.#flush();
		}, delay);
	}

	/** Watches the oldest command out there; a socket that has gone silent is treated as dead. */
	#armTimeout(): void {
		if (this.#timeout || this.#pending.length === 0) return;

		this.#timeout = setTimeout(() => {
			this.#timeout = null;
			// Only closes it: dropping the socket and reconnecting is what the close handler does,
			// and doing it here as well would spend one of the attempts on nothing.
			this.#socket?.close();
		}, ANSWER_TIMEOUT);
	}

	#clearTimeout(): void {
		if (this.#timeout) clearTimeout(this.#timeout);
		this.#timeout = null;
	}

	#clearTimers(): void {
		this.#clearTimeout();
		if (this.#reconnecting) clearTimeout(this.#reconnecting);
		this.#reconnecting = null;
	}

	#failPending(cause: Error): void {
		const pending = this.#pending;
		this.#pending = [];
		for (const waiting of pending) waiting.reject(cause);
	}
}

/** The socket's absolute url, off the page's own origin, `ws` or `wss` to match it. */
function socketUrl(): string {
	const url = new URL(PATH, location.href);
	url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:';

	return url.toString();
}
