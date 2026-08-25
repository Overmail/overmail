import { mailBodyText } from '$lib/app/mails/body';
import { mailRepository, type Mail } from '$lib/repository/MailRepository';

/** Where a mail stands with spam, and what put it there. */
export type SpamState = {
	is_spam: boolean;
	/** When it was last flagged or unflagged, absent for a mail nobody ever flagged. */
	changed_at?: string | null;
	/** The filter that caught it, absent when a reader flagged it themselves. */
	filter?: { id: string; name: string } | null;
};

/** What the agent read out of the mail. A field it could not fill is absent, which is an answer. */
export type SenderAnalysis = {
	person?: string | null;
	organisation?: string | null;
	/** Why there is nothing, for the case where the model could not be asked at all. */
	failure?: string | null;
};

/**
 * What the socket sends, told apart by `type`: the mail and its state on opening and on every
 * change, the agent's reading of it once, whenever it is done.
 */
type DetailEvent =
	| { type: 'mail'; mail: Mail; spam: SpamState }
	| ({ type: 'sender_analysis' } & SenderAnalysis);

/**
 * How the screen is doing, which is not the same as how the mail is doing: `offline` still shows
 * the mail it last had, it just no longer promises that it is current.
 */
export type DetailStatus = 'connecting' | 'live' | 'offline' | 'gone';

/** How long before reopening a socket that dropped, per attempt; the last one repeats. */
const RECONNECT_DELAYS = [1_000, 2_000, 5_000, 10_000];

/**
 * One mail, and everything known about it, kept current for as long as the screen is open.
 *
 * The headers and the state come over a socket: a mail is filed, archived or caught by a filter
 * while somebody is reading it, and every one of those is a row another screen may write. What the
 * agent reads out of the mail comes over the same socket, once, whenever the model is done. The
 * body comes over its own request and is asked for once -- it is big and it does not change.
 */
export class EmailDetailStore {
	readonly #id: string;

	#socket: WebSocket | null = null;
	#attempt = 0;
	#reconnecting: ReturnType<typeof setTimeout> | null = null;
	/** Set by [close]: the screen is gone, so nothing reopens the socket. */
	#closed = false;

	/** The mail as the server last reported it, null until the first event arrives. */
	mail = $state<Mail | null>(null);

	/** Where it stands with spam, null until then. */
	spam = $state<SpamState | null>(null);

	/** The body as text, absent while its own request is still out. */
	body = $state<string | undefined>(undefined);

	status = $state<DetailStatus>('connecting');

	/**
	 * What the agent read as the sender, null while it is still reading. One answer per open
	 * screen: nothing about it changes while the mail is being read.
	 */
	sender = $state<SenderAnalysis | null>(null);

	constructor(id: string) {
		this.#id = id;
	}

	/** Opens the socket and asks for the body. Calling it twice does nothing the second time. */
	open(): void {
		if (this.#socket || this.#closed) return;

		this.#connect();
		void this.#loadBody();
	}

	/** Hangs up for good. Leaving the screen calls this. */
	close(): void {
		this.#closed = true;
		if (this.#reconnecting) clearTimeout(this.#reconnecting);
		this.#reconnecting = null;

		const socket = this.#socket;
		this.#socket = null;
		socket?.close();
	}

	#connect(): void {
		// A reconnect asks the agent again: its answer rides on the socket, and a socket that
		// dropped before it arrived would otherwise leave the screen waiting forever.
		this.sender = null;

		const socket = new WebSocket(socketUrl(this.#id));
		this.#socket = socket;

		socket.addEventListener('open', () => {
			this.#attempt = 0;
			this.status = 'live';
		});

		socket.addEventListener('message', (event) => {
			if (typeof event.data !== 'string') return;

			const detail = JSON.parse(event.data) as DetailEvent;

			if (detail.type === 'mail') {
				this.mail = detail.mail;
				this.spam = detail.spam;
			} else {
				this.sender = detail;
			}

			this.status = 'live';
		});

		socket.addEventListener('close', (event) => {
			this.#socket = null;
			if (this.#closed) return;

			// The server closes normally for a mail that is not there -- or not theirs, which reads
			// the same on purpose. Reopening would only be told the same thing again.
			if (event.code === 1000 && !this.mail) {
				this.status = 'gone';
				return;
			}

			this.status = 'offline';
			this.#scheduleReconnect();
		});
	}

	#scheduleReconnect(): void {
		if (this.#closed || this.#reconnecting) return;

		const delay = RECONNECT_DELAYS[Math.min(this.#attempt, RECONNECT_DELAYS.length - 1)];
		this.#attempt++;
		this.#reconnecting = setTimeout(() => {
			this.#reconnecting = null;
			if (!this.#closed) this.#connect();
		}, delay);
	}

	async #loadBody(): Promise<void> {
		try {
			this.body = mailBodyText(await mailRepository.getContent(this.#id));
		} catch {
			// Left absent, which the screen shows as a body that has not arrived. The headers and
			// the state are the point of this page; one body that did not load must not empty it.
		}
	}
}

function socketUrl(id: string): string {
	const url = new URL(`/api/webapp/email/${id}`, location.href);
	url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:';

	return url.toString();
}
