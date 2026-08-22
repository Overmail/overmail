import type { MailParticipant } from '$lib/repository/MailRepository';

/** What a person is called in the list. The address stands in when the header carried no name. */
export const displayNameOf = (participant: MailParticipant): string =>
	participant.name?.trim() || participant.address;

/**
 * The two letters an avatar falls back to. Splits on the separators both names and addresses use,
 * so `julius.babies@…` reads as JB rather than as J.
 */
export const initialsOf = (displayName: string): string =>
	displayName
		.split(/[\s@._-]+/)
		.filter(Boolean)
		.slice(0, 2)
		.map((part) => part[0].toUpperCase())
		.join('');
