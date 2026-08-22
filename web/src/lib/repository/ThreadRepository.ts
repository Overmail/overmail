const API = '/api/threads';

/** One thread, with what it holds rather than the mails themselves. */
export type ThreadOverview = {
	id: string;
	title: string;
	/** Send time of the newest mail in it, which is what threads are ranked by. ISO-8601. */
	last_sent_at: string;
	/** Its mails, newest first, in the order a list shows them. */
	mail_ids: string[];
};

/**
 * The threads of the mailbox. Only the skeleton -- the mails themselves come from
 * `MailRepository`, which is what `ids` is on its query for.
 */
export class ThreadRepository {
	/**
	 * Every thread, the one with the newest mail first. The whole list rather than a page of it: a
	 * thread's mails sit wherever they were sent, so which thread comes next cannot be worked out
	 * from a stretch of the mailbox. Ids rather than mails is what keeps that affordable.
	 */
	async listThreads(): Promise<ThreadOverview[]> {
		const response = await fetch(API, { credentials: 'include' });
		if (!response.ok) throw new Error(`Could not list threads: ${response.status}`);

		const body = (await response.json()) as { threads: ThreadOverview[] };
		return body.threads;
	}
}

export const threadRepository = new ThreadRepository();
