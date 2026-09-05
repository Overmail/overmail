import type {EmailParticipant} from "$lib/repository/EmailRepository.svelte";

/**
 * How a correspondent is named where there is room for one line: the display name their mail
 * carried, and the bare address for the ones that carried none.
 */
export function displayName(participant: EmailParticipant): string {
	return participant.name ?? participant.address;
}

/**
 * Both, for where there is room for both: the name says who it is, the address says which one.
 * A correspondent without a name is their address and nothing else -- not an empty pair of
 * brackets after it.
 */
export function spelledOut(participant: EmailParticipant): string {
	return participant.name ? `${participant.name} (${participant.address})` : participant.address;
}

/**
 * Whether a participant is the reader themselves.
 *
 * Against every address they receive mail under, not just the one their account carries: a mail
 * to the address of one of their mail accounts is a mail to them. Compared case-insensitively on
 * both sides -- nobody treats their own address as case-sensitive, and a mail addressed to
 * `Julius@` is still addressed to `julius@`.
 *
 * Null for [self] while the account has not been read yet: then nobody is the reader, and the
 * only thing that changes once it is there is that a name turns into "you".
 */
export function isSelf(participant: EmailParticipant, self: {addresses: string[]} | null): boolean {
	if (self === null) return false;

	const address = participant.address.toLowerCase();
	return self.addresses.some((known) => known.toLowerCase() === address);
}
