import type { SpamRule } from '$lib/app/spam_dialog/rule';

const API = '/api/mails';

/** Someone a mail names, as that mail spelled them out. */
export type MailParticipant = {
	address: string;
	/** Display name from this mail, null for a bare address. */
	name: string | null;
};

/** A tag a mail is filed under. */
export type MailTag = {
	id: string;
	name: string;
};

/** The matter a mail sits in. */
export type MailThread = {
	id: string;
	title: string;
	/** Mails the thread holds altogether, not the ones of it that are loaded. */
	size: number;
};

/** A mail as the listing reports it — headers only, no body. */
export type Mail = {
	id: string;
	subject: string;
	sender: MailParticipant;
	/** The `To` field. */
	recipients: MailParticipant[];
	cc: MailParticipant[];
	bcc: MailParticipant[];
	/** ISO-8601, whole seconds. */
	sent_at: string;
	is_read: boolean;
	/** Whether the mail sits in the archive. */
	is_archived: boolean;
	/** The matter the mail sits in, absent while nothing has filed it. */
	thread?: MailThread | null;
	tags: MailTag[];
};

/** One page of mails, and how much there is to page through. */
export type MailPage = {
	/** In the requested order; shorter than the requested limit once the window runs out. */
	mails: Mail[];
	/** Mails matching `after` and `before`, this page included and limit and sort ignored. */
	total: number;
};

/** The body of one mail, in the shapes it carried. */
export type MailContent = {
	id: string;
	/** The plain text part, null when the mail carried none. */
	text: string | null;
	/** The HTML part, null when the mail carried none. */
	html: string | null;
};

/** Which end of the mailbox a page is read from. */
export type MailSort = 'desc' | 'asc';

/** The window of the mailbox a page is cut out of. */
export type MailPageQuery = {
	limit?: number;
	/** Exclusive lower bound on the send time, ISO-8601 or epoch seconds. */
	after?: string;
	/** Exclusive upper bound on the send time, ISO-8601 or epoch seconds. */
	before?: string;
	/**
	 * `desc` (newest first) by default. `asc` reads from the other end, which is how the bottom of
	 * a long mailbox is reached without paging through everything above it.
	 */
	sort?: MailSort;
	/**
	 * Narrows the window to one matter. Its mails can sit anywhere in the mailbox, so `total` then
	 * counts the thread rather than the mailbox.
	 */
	thread?: string;
	/**
	 * Narrows the window to a named handful. At most 200 per request -- they travel in the query
	 * string. `total` then counts the ones that matched.
	 */
	ids?: string[];
	/** Narrows the window to the mails that sit in some thread, or to the ones that sit in none. */
	filed?: boolean;
};

/** Whether a rule would catch one mail, as `POST /api/mails/{id}/validate-rule` reports it. */
type RuleMatch = {
	matches: boolean;
};

/** The most ids one request may name, as the server caps it. */
export const MAX_IDS_PER_REQUEST = 200;

/** The mailbox as a list. Paging and change tracking live in `MailStore`, not here. */
export class MailRepository {
	/** One page of mails, in the requested order, with the size of the window it came from. */
	async listMails(query: MailPageQuery = {}): Promise<MailPage> {
		const params = new URLSearchParams();
		if (query.limit !== undefined) params.set('limit', String(query.limit));
		if (query.after !== undefined) params.set('after', query.after);
		if (query.before !== undefined) params.set('before', query.before);
		if (query.sort !== undefined) params.set('sort', query.sort);
		if (query.thread !== undefined) params.set('thread', query.thread);
		if (query.ids !== undefined) params.set('ids', query.ids.join(','));
		if (query.filed !== undefined) params.set('filed', String(query.filed));

		const search = params.size === 0 ? '' : `?${params}`;
		const response = await fetch(`${API}${search}`, { credentials: 'include' });
		if (!response.ok) throw new Error(`Could not list mails: ${response.status}`);

		return (await response.json()) as MailPage;
	}

	/**
	 * The body of one mail.
	 *
	 * Its own request, as the route is: a listing carries no bodies, so whoever wants to show one
	 * asks for it per mail once it is about to be shown.
	 */
	async getContent(id: string): Promise<MailContent> {
		const response = await fetch(`${API}/${id}/content`, { credentials: 'include' });
		if (!response.ok) throw new Error(`Could not load the body of ${id}: ${response.status}`);

		return (await response.json()) as MailContent;
	}

	/**
	 * Whether a spam rule would catch one mail. Nothing is stored and nothing is filed -- this is
	 * the question the editor asks while a rule is being written.
	 *
	 * `signal` aborts a check that a later one has overtaken: the rule changes while it is typed,
	 * and only the answer to the newest version is worth waiting for.
	 */
	async validateRule(id: string, rule: SpamRule, signal?: AbortSignal): Promise<boolean> {
		const response = await fetch(`${API}/${id}/validate-rule`, {
			method: 'POST',
			credentials: 'include',
			headers: { 'content-type': 'application/json' },
			body: JSON.stringify(rule),
			signal
		});
		if (!response.ok) throw new Error(`Could not check a rule against ${id}: ${response.status}`);

		return ((await response.json()) as RuleMatch).matches;
	}
}

export const mailRepository = new MailRepository();
