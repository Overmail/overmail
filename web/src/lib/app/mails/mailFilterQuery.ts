import type {ViewFilter} from "$lib/repository/ViewSocket";

/**
 * A view's filter as the listing endpoints read it: one parameter per attribute, with the names
 * `ViewSettings.Filter` has on the wire.
 *
 * What is null is left out, because absent is what "no restriction" means there -- and an
 * attribute that is set but empty is sent as an empty value, which lets nothing through. The
 * difference is the whole point of the filter, so it survives the trip.
 *
 * The values within one parameter are sorted, so the same filter always spells the same url --
 * see [filterKey], which is what a cache is keyed by.
 */
export function filterParams(filter: ViewFilter): URLSearchParams {
    const params = new URLSearchParams();

    if (filter.archivedState !== null) params.set("archived_state", [...filter.archivedState].sort().join(","));
    if (filter.readState !== null) params.set("read_state", String(filter.readState));
    if (filter.imapAccountIds !== null) params.set("imap_account_ids", [...filter.imapAccountIds].sort().join(","));
    if (filter.sentBy !== null) params.set("sent_by", [...filter.sentBy].sort().join(","));
    if (filter.sentTo !== null) params.set("sent_to", [...filter.sentTo].sort().join(","));
    if (filter.hasLabels !== null) params.set("has_labels", [...filter.hasLabels].sort().join(","));

    return params;
}

/**
 * What identifies a filter: two filters that ask for the same mails have the same key.
 *
 * Everything a listing holds hangs off this -- which pages were read, how long the list is, which
 * stretch was picked -- because none of that means anything under another filter. It is the url
 * the listing is read with, which is exactly the thing that decides that.
 */
export function filterKey(filter: ViewFilter): string {
    const params = filterParams(filter);
    params.sort();

    return params.toString();
}
