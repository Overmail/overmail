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
	/** What of the reading was filed as tags, and why. Empty where nothing could be read. */
	tags?: FiledTag[] | null;
	/** Why there is nothing, for the case where the model could not be asked at all. */
	failure?: string | null;
};

/**
 * What the mail carries as a way into somewhere: a one-time code, a link that signs the reader in.
 *
 * Unlike the reading above, this one is already written down by the time it arrives -- the server
 * files these itself, because whether a mail carries a code is a reading and not an opinion. What
 * comes down the socket is the screen being told what landed in the table.
 */
export type MagicEmail = {
	/** Who it lets the reader into, absent for the mail that lets nobody in. */
	provider?: string | null;
	/** `code`, `link`, or both. Empty for the great majority of mail, which is neither. */
	kinds?: string[] | null;
	/** When it stops working, absent where the mail never said how long it works for. */
	valid_until?: string | null;
	/** Why there is nothing, for the case where the model could not be asked at all. */
	failure?: string | null;
};

/**
 * One tag the agent attached, and why.
 *
 * The tag is already on the mail by the time this arrives -- the server files them itself, like the
 * magic rows -- so this is not a proposal to accept. What it adds over the mail's own tag list is
 * why: one sentence, and where the step quoted the mail for it, the words themselves. That is the
 * part a reader needs to disagree with a tag.
 *
 * Shared by the two steps that file tags: the sender reading, whose labels are the names it found,
 * and the topic reading, whose labels are quoted out of the text.
 */
export type FiledTag = {
	tag: string;
	/** What it was read off, in one sentence. */
	reason: string;
	/** The words of the mail behind it, absent where the step quoted none. */
	quote?: string | null;
};

/** What the agent made of what the mail is about, and what ties it to the rest of its matter. */
export type TopicAnalysis = {
	/**
	 * What it proposed for the mail, the most general first, and not what the mail carries: the
	 * revision step holds these against the mailbox's own vocabulary and files what it agrees with.
	 * Empty for mail that fits no label.
	 */
	tags?: FiledTag[] | null;
	/** What was filed outright, which is the identifier's own tag and nothing else. */
	filed_tags?: FiledTag[] | null;
	/** `noted` for the first mail of a matter, `opened` for the second, `joined` after that. */
	matter?: string | null;
	/** The identifier of the matter, absent for the great majority of mail. */
	identifier?: string | null;
	/** Which kind of identifier: `invoice`, `order`, `conversation`, ... */
	identifier_kind?: string | null;
	/** Why there is nothing, for the case where the model could not be asked at all. */
	failure?: string | null;
};

/**
 * What the agent changed about the mailbox after looking at the mail that came before this one.
 *
 * Not a reading: this step works rather than answers, and by the time it arrives the tags and the
 * threads it touched are already rows -- they come down the mail's own socket like everything else
 * about the mail. What is here is the account of it, for a reader who wants to know what just
 * happened and why.
 */
export type Revision = {
	/** What was changed, one line each. Empty where nothing was, which is the usual case. */
	changes?: string[] | null;
	/** The agent's closing sentence, absent where it never got to one. */
	said?: string | null;
	/** False where the step was not worth starting: no tags and no identifier to search on. */
	ran?: boolean;
	/**
	 * True where the step never decided about the proposed tags and they were filed as proposed --
	 * the mail keeps its tags, but nothing checked them against the words the mailbox already uses.
	 */
	proposals_filed_as_proposed?: boolean;
	/** Why there is nothing, for the case where the model could not be asked at all. */
	failure?: string | null;
};

/**
 * Who said one line of an agent run. `error` is nobody: it is why there is no answer, and
 * `thinking` is the channel a model reports its reasoning on -- which on some backends is where
 * the answer itself turns up, see `answerText` on the server.
 *
 * `tool_call` and `tool_result` only ever appear on the step that works with tools: the model asking
 * for something to be done, and what came back from doing it.
 */
export type AgentRole =
	| 'system'
	| 'user'
	| 'assistant'
	| 'thinking'
	| 'tool_call'
	| 'tool_result'
	| 'error';

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

/** What the mail's own socket sends: the mail and its state, on opening and on every change. */
type DetailEvent = { type: 'mail'; mail: Mail; spam: SpamState };

/**
 * What the agent's socket sends, told apart by `type`: a start, a line per thing said, the readings
 * the run made, and an end.
 */
type AgentEvent =
	| { type: 'agent_started' }
	| { type: 'agent_finished' }
	| ({ type: 'agent_message' } & AgentMessage)
	| ({ type: 'sender_analysis' } & SenderAnalysis)
	| ({ type: 'magic_email' } & MagicEmail)
	| ({ type: 'topic_analysis' } & TopicAnalysis)
	| ({ type: 'revision' } & Revision);

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
 * Two sockets, because they are two different kinds of thing. The mail's own is a subscription: it
 * opens with the screen, it never ends of its own accord, and it reopens when it drops, because a
 * mail is filed, archived or caught by a filter while somebody is reading it and every one of those
 * is a row another screen may write. The agent's is a piece of work somebody started -- see
 * [readMail] -- it runs once and closes, and it is not reopened, because a run is a button press
 * and starting one minutes later off a reconnect is not what the reader pressed it for.
 *
 * The body comes over its own request and is asked for once: it is big and it does not change.
 */
export class EmailDetailStore {
	readonly #id: string;

	#socket: WebSocket | null = null;
	#attempt = 0;
	#reconnecting: ReturnType<typeof setTimeout> | null = null;
	/** The run in flight, null when none is. */
	#agent: WebSocket | null = null;
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
	 * What the agent read as the sender, null until a run has finished that step. One answer per
	 * run, and a run happens when, and only when, [readMail] is called.
	 */
	sender = $state<SenderAnalysis | null>(null);

	/** What the agent found as a way into somewhere, null until a run has finished that step. */
	magic = $state<MagicEmail | null>(null);

	/**
	 * What the agent made of what the mail is about, null until a run has finished that step.
	 *
	 * The tags themselves arrive on the mail's own socket as well, because the server attaches them:
	 * `mail.tags` is what the mail carries, this is what the last run said and why.
	 */
	topic = $state<TopicAnalysis | null>(null);

	/** What the agent changed about the mailbox on the last run, null until it has finished. */
	revision = $state<Revision | null>(null);

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

	/** Opens the mail's socket and asks for the body. Calling it twice does nothing the second time. */
	open(): void {
		if (this.#socket || this.#closed) return;

		this.#connect();
		void this.#loadBody();
	}

	/** Hangs up for good, the run in flight included. Leaving the screen calls this. */
	close(): void {
		this.#closed = true;
		if (this.#reconnecting) clearTimeout(this.#reconnecting);
		this.#reconnecting = null;

		const socket = this.#socket;
		this.#socket = null;
		socket?.close();

		const agent = this.#agent;
		this.#agent = null;
		agent?.close();
	}

	/**
	 * Asks the agent to read the mail, from the top, whether or not it has read it before.
	 *
	 * Its own socket per run, which is what makes a rerun free to describe: the old one is closed,
	 * and the server's run goes with the session rather than having to be cancelled. Nothing here
	 * waits for the mail's socket -- the two are independent, and a run is worth starting even on a
	 * screen that has lost its subscription.
	 */
	readMail(): void {
		if (this.#closed) return;

		// Whatever was running is dropped rather than waited for: what the reader asked for is a
		// reading of the mail as it is now, not the one they asked for a moment ago.
		this.#agent?.close();

		const agent = new WebSocket(agentUrl(this.#id));
		this.#agent = agent;
		// Said here rather than on `agent_started`, so the button goes quiet on the click rather
		// than once a socket somewhere has been opened.
		this.analysing = true;

		agent.addEventListener('open', () => {
			agent.send(JSON.stringify({ type: 'read_mail' }));
		});

		agent.addEventListener('message', (event) => {
			// A frame from the run this one replaced. Its log belongs to a reading nobody is
			// waiting for any more.
			if (this.#agent !== agent) return;
			if (typeof event.data !== 'string') return;

			this.#onAgentEvent(JSON.parse(event.data) as AgentEvent);
		});

		agent.addEventListener('close', () => {
			if (this.#agent !== agent) return;

			this.#agent = null;
			// Whether it ended by finishing or by falling over: either way nothing is running, and
			// a screen left waiting on a backend that hung up would wait for good. What a finished
			// run said is kept -- it is still what the agent made of this mail.
			this.analysing = false;
		});
	}

	#onAgentEvent(event: AgentEvent): void {
		if (event.type === 'agent_started') {
			// A run that starts replaces the one before it, log and readings both: what the last
			// one made of the mail is not what this one is going to make of it.
			this.log = [];
			this.sender = null;
			this.magic = null;
			this.topic = null;
			this.revision = null;
			this.analysing = true;
		} else if (event.type === 'agent_finished') {
			this.analysing = false;
		} else if (event.type === 'agent_message') {
			this.log.push(event);
		} else if (event.type === 'sender_analysis') {
			this.sender = event;
		} else if (event.type === 'magic_email') {
			this.magic = event;
		} else if (event.type === 'topic_analysis') {
			this.topic = event;
		} else {
			this.revision = event;
		}
	}

	#connect(): void {
		const socket = new WebSocket(socketUrl(this.#id));
		this.#socket = socket;

		socket.addEventListener('open', () => {
			this.#attempt = 0;
			this.status = 'live';
		});

		socket.addEventListener('message', (event) => {
			if (typeof event.data !== 'string') return;

			const detail = JSON.parse(event.data) as DetailEvent;

			this.mail = detail.mail;
			this.spam = detail.spam;
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
	return wsUrl(`/api/webapp/email/${id}`);
}

function agentUrl(id: string): string {
	return wsUrl(`/api/webapp/email/${id}/agent`);
}

function wsUrl(path: string): string {
	const url = new URL(path, location.href);
	url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:';

	return url.toString();
}
