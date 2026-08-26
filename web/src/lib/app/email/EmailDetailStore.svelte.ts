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
	/** The platform the mail came through -- "GitHub" and the like -- absent for ordinary mail. */
	via?: string | null;
	/**
	 * Handles on what the mail belongs to: `gh:acme/widgets#412`, a newsletter's name, or a bare
	 * kind word where the mail shows nothing more specific. Empty for mail that belongs to nothing.
	 */
	context?: string[] | null;
	/** Why there is nothing, for the case where the model could not be asked at all. */
	failure?: string | null;
};

/**
 * Who said one line of an agent run. `error` is nobody: it is why there is no answer, and
 * `thinking` is the channel a model reports its reasoning on -- which on some backends is where
 * the answer itself turns up, see `answerText` on the server.
 */
export type AgentRole = 'system' | 'user' | 'assistant' | 'thinking' | 'error';

/**
 * One line of what the agent was asked and what it said. The prompts verbatim and the answer
 * unparsed -- this is a log to read, and for now it is only here to be read.
 */
export type AgentMessage = {
	step: string;
	/** 1 for the first ask, 2 for the one carrying a complaint about the first answer. */
	attempt: number;
	role: AgentRole;
	text: string;
	/** What the request cost, on the answer that came back. */
	input_tokens?: number | null;
	output_tokens?: number | null;
};

/**
 * What the socket sends, told apart by `type`: the mail and its state on opening and on every
 * change, and an agent run as it happens once one was asked for -- a start, a line per thing said,
 * and the reading it ended with.
 */
type DetailEvent =
	| { type: 'mail'; mail: Mail; spam: SpamState }
	| { type: 'agent_started' }
	| ({ type: 'agent_message' } & AgentMessage)
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
 * agent reads out of the mail comes over the same socket, but only once somebody asked for it with
 * [analyse] -- opening a mail is not the same as wanting a model run over it. The body comes over
 * its own request and is asked for once: it is big and it does not change.
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
	 * What the agent read as the sender, null until a run has finished one. One answer per run, and
	 * a run happens when, and only when, [analyse] is called.
	 */
	sender = $state<SenderAnalysis | null>(null);

	/**
	 * Everything the agent was asked and everything it answered on the current run, oldest first.
	 * Emptied when a run starts, so it is one run's log rather than a pile of them.
	 */
	log = $state<AgentMessage[]>([]);

	/** Whether a run is in flight, which is what a rerun has to wait for. */
	analysing = $state(false);

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

	/**
	 * Asks the agent to read the mail, from the top, whether or not it has read it before.
	 *
	 * Does nothing while the socket is down: the ask is not queued, because a run is something
	 * somebody pressed a button for and starting one minutes later off a reconnect is not what they
	 * pressed it for. The button comes back with the socket.
	 */
	analyse(): void {
		if (this.#socket?.readyState !== WebSocket.OPEN) return;

		this.#socket.send(JSON.stringify({ type: 'analyse' }));
	}

	#connect(): void {
		// Whatever was running died with the socket, and nothing on the new one picks it up: a run
		// is asked for, so a screen that thought one was in flight has to stop waiting for it. What
		// a finished run said is kept -- it is still what the agent made of this mail, and a
		// dropped connection is no reason to blank it.
		this.analysing = false;

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
			} else if (detail.type === 'agent_started') {
				// A run that starts replaces the one before it, log and reading both: what the
				// last one made of the mail is not what this one is going to make of it.
				this.log = [];
				this.sender = null;
				this.analysing = true;
			} else if (detail.type === 'agent_message') {
				this.log.push(detail);
			} else {
				// The reading is also what ends the run, see the socket.
				this.sender = detail;
				this.analysing = false;
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
