import type { SpamRule } from '$lib/app/spam_dialog/rule';

const API = '/api/filters';

/** One stored spam filter, as the server reports it. */
export type SpamFilterRecord = {
	id: string;
	name: string;
	/** The tree itself, in the shape the editor builds and reads. */
	rule: SpamRule;
	/** Whether the filter files new mail. */
	is_active: boolean;
	/** ISO-8601. */
	created_at: string;
};

/** What a filter is written or overwritten with. A write replaces everything a filter says. */
export type SpamFilterDraft = {
	name: string;
	rule: SpamRule;
	is_active?: boolean;
	/**
	 * The mail the filter was written for. That mail is flagged as spam before the filter exists --
	 * the reader decides first and writes the rule afterwards -- so naming it here is what makes
	 * the filter the reason for the flag instead of the reader's own hand.
	 */
	mail?: string;
};

/** The spam filters, and the two questions asked around saving one. */
export class FilterRepository {
	/** Every filter of the signed-in user, oldest first, switched off ones included. */
	async listFilters(): Promise<SpamFilterRecord[]> {
		const response = await fetch(API, { credentials: 'include' });
		if (!response.ok) throw new Error(`Could not list the filters: ${response.status}`);

		return ((await response.json()) as { filters: SpamFilterRecord[] }).filters;
	}

	async createFilter(draft: SpamFilterDraft): Promise<SpamFilterRecord> {
		const response = await fetch(API, {
			method: 'POST',
			credentials: 'include',
			headers: { 'content-type': 'application/json' },
			body: JSON.stringify(draft)
		});
		if (!response.ok) throw new Error(`Could not save the filter: ${response.status}`);

		return (await response.json()) as SpamFilterRecord;
	}

	async updateFilter(id: string, draft: SpamFilterDraft): Promise<SpamFilterRecord> {
		const response = await fetch(`${API}/${id}`, {
			method: 'PUT',
			credentials: 'include',
			headers: { 'content-type': 'application/json' },
			body: JSON.stringify(draft)
		});
		if (!response.ok) throw new Error(`Could not save the filter ${id}: ${response.status}`);

		return (await response.json()) as SpamFilterRecord;
	}

	/**
	 * How many mails a rule would catch across the whole mailbox, `ignoreMail` left out of the
	 * count -- that one is the mail the rule was written for, which the caller already knows about.
	 *
	 * Asked before saving: a rule written while reading one mail that also catches thirty others is
	 * worth saying out loud first. Nothing is written and nothing is flagged.
	 */
	async countAffectedMails(rule: SpamRule, ignoreMail?: string): Promise<number> {
		const response = await fetch(`${API}/affected-mails`, {
			method: 'POST',
			credentials: 'include',
			headers: { 'content-type': 'application/json' },
			body: JSON.stringify({ rule, ignore_mail: ignoreMail })
		});
		if (!response.ok) throw new Error(`Could not check the rule: ${response.status}`);

		return ((await response.json()) as { count: number }).count;
	}

	/**
	 * Holds a saved filter against the mails that are already there and flags what it catches.
	 * Answers how many mails changed, which is fewer than the filter catches when some of them
	 * were spam already.
	 */
	async applyFilter(id: string): Promise<number> {
		const response = await fetch(`${API}/${id}/apply`, {
			method: 'POST',
			credentials: 'include'
		});
		if (!response.ok) throw new Error(`Could not apply the filter ${id}: ${response.status}`);

		return ((await response.json()) as { flagged: number }).flagged;
	}
}

export const filterRepository = new FilterRepository();
