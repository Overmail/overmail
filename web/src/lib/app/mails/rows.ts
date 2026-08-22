import type { Mail, MailParticipant, MailThread } from '$lib/repository/MailRepository';
import type { ThreadOverview } from '$lib/repository/ThreadRepository';

export type MailRow = { kind: 'mail'; id: string; mail: Mail; grouped: boolean };

/**
 * A header. [thread] is null for the mails nothing has filed, which form a group of their own.
 *
 * [loaded] is how many of the group's mails this list holds, [total] how many it has. They differ
 * while a thread reaches further back than the loaded stretch does.
 *
 * [participants] is everyone on the group's mails, once each. Empty for the unfiled group: that is
 * a leftover bin rather than an exchange, and the faces of everybody who ever wrote say nothing.
 */
export type MailGroupRow = {
	kind: 'group';
	id: string;
	thread: MailThread | null;
	loaded: number;
	total: number;
	participants: MailParticipant[];
};

/**
 * One row per mail the list holds -- never one per mail the server has. The mails past those are
 * the table's pending tail: they hold a row's height so the list has its final height from the
 * start, and building a row object for each of them would mean building thousands of them on every
 * page that arrives.
 *
 * Grouping adds a header row above each group. Headers are rows like any other rather than a
 * nested table, so the windowing and the row height keep working unchanged.
 */
export type MailTableRow = MailGroupRow | MailRow;

export const mailRow = (mail: Mail): MailRow => ({
	kind: 'mail',
	id: mail.id,
	mail,
	grouped: false
});

/**
 * The rows the table has data for, when the list is arranged by thread.
 *
 * One header per thread and one row per mail that is here -- never one per mail the thread has.
 * The layout is the skeleton's business (see `ThreadedMailStore`), which is what says where a mail
 * sits before it is loaded; these are only the rows the table can render cells for, looked up by
 * id.
 *
 * @param threads the skeleton, newest thread first.
 * @param mailOf what is loaded, by mail id.
 * @param unfiled the loaded mails that sit in no thread, in list order.
 * @param unfiledTotal how many there are altogether. The header exists as soon as there is one,
 *   loaded or not: the layout reserves its row either way, and a header missing from these rows
 *   would leave a placeholder where the group opens.
 */
export function buildThreadRows(
	threads: ThreadOverview[],
	mailOf: (id: string) => Mail | undefined,
	unfiled: Mail[],
	unfiledTotal: number
): MailTableRow[] {
	const rows: MailTableRow[] = [];

	for (const thread of threads) {
		const held = thread.mail_ids.map(mailOf).filter((mail) => mail !== undefined);

		rows.push({
			kind: 'group',
			id: `thread-${thread.id}`,
			thread: { id: thread.id, title: thread.title, size: thread.mail_ids.length },
			loaded: held.length,
			total: thread.mail_ids.length,
			participants: participantsOf(held)
		});

		for (const mail of held) rows.push({ ...mailRow(mail), grouped: true });
	}

	if (unfiledTotal > 0) {
		rows.push({
			kind: 'group',
			id: 'thread-none',
			thread: null,
			loaded: unfiled.length,
			total: unfiledTotal,
			participants: []
		});

		for (const mail of unfiled) rows.push({ ...mailRow(mail), grouped: true });
	}

	return rows;
}

/**
 * Everybody on the mails of a thread, one entry each.
 *
 * Deduplicated by address, because the same correspondent is on nearly every mail of an exchange
 * and six copies of one face say less than one does. Walked oldest mail first and sender before
 * recipients, so the row starts with whoever opened the exchange rather than with whoever answered
 * last.
 *
 * **The mailbox owner is in here like anybody else.** Which address on a mail is theirs is not
 * something the listing says, and dropping the login address instead would remove the wrong person
 * from an aliased mailbox.
 *
 * Only over the mails that are loaded. A thread reaching past them names its own size in the
 * header count; the faces speak for what is on screen.
 */
function participantsOf(mails: Mail[]): MailParticipant[] {
	const byAddress = new Map<string, MailParticipant>();

	// [mails] arrives newest first, so this reads the exchange from its start.
	for (let index = mails.length - 1; index >= 0; index--) {
		const mail = mails[index];
		for (const person of [mail.sender, ...mail.recipients, ...mail.cc]) {
			if (!byAddress.has(person.address)) byAddress.set(person.address, person);
		}
	}

	return [...byAddress.values()];
}
