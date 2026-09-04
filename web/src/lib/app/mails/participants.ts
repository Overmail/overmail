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
