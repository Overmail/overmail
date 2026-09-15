import type {EmailParticipant} from "$lib/repository/EmailRepository.svelte";

/**
 * One correspondent the search turned up, with how much of their mail there is.
 *
 * The same shape a mail's participants have -- it is the same person, and the chips and avatars
 * that show one work off that type already. What identifies them is the id: an address book entry
 * of this account, which is what a filter over senders or recipients stores.
 */
export type SenderSearchResult = EmailParticipant & {emailCount: number};

/**
 * The correspondents whose name or address matches [query], as the server ranks them.
 *
 * An empty query is not an empty answer: it is what to offer somebody who has not typed yet --
 * the ones they hear from most. A request that fails is nobody rather than an error, the same way
 * [findLabels] handles it: every caller is a list that can be empty and none of them can do
 * anything about it.
 *
 * The rows come out of the mail that arrived, so this finds people who have written -- an address
 * that only ever appeared in a `To` has no row of its own to find.
 */
export async function findSenders(query: string): Promise<SenderSearchResult[]> {
    const response = await fetch(`/api/senders/search?query=${encodeURIComponent(query)}`);
    if (!response.ok) return [];

    const data: {
        senders: {
            id: string;
            name: string | null;
            address: string;
            avatar_url: string | null;
            avatar_padding: number | null;
            email_count: number;
        }[];
    } = await response.json();

    return data.senders.map((sender) => ({
        id: sender.id,
        name: sender.name,
        address: sender.address,
        avatarUrl: sender.avatar_url,
        avatarPadding: sender.avatar_padding,
        emailCount: sender.email_count,
    }));
}
