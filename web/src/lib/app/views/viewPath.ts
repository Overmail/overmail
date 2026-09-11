import {parseSlugId, slugParam} from "$lib/app/mails/emailPath";

/**
 * Which view the listing is showing: `/?view=<slug>`, which is what makes a view something you
 * can link to, reload and step back out of -- the same arrangement `?email=` has for a mail.
 */
export const VIEW_PARAM = "view";

/**
 * How a view is spelled in a url: the beginning of its name, then the id without its hyphens.
 *
 * The name is there for the reader and is not read back -- a view that was renamed is still the
 * same view, and [parseViewId] only looks at the id at the end.
 */
export function viewSlug(id: string, name?: string | null): string {
    return slugParam(id, name);
}

/** The view a `?view=` value is about, as the uuid the repository knows. Null when it is none. */
export function parseViewId(value: string | null | undefined): string | null {
    return parseSlugId(value);
}

/** The view the url [url] has open, or null when it is showing no view. */
export function openViewId(url: URL): string | null {
    return parseViewId(url.searchParams.get(VIEW_PARAM));
}

/**
 * Where a view is shown: the listing, with the view in the query.
 *
 * Built on the root and not on [current], so nothing of the url it is left from travels along --
 * in particular not the `?email=` of an open mail. Opening a view is leaving that mail, not
 * keeping it beside a different listing. [current] is only what the url is resolved against.
 */
export function viewUrl(id: string, name: string | null | undefined, current: URL): URL {
    const url = new URL("/", current);
    url.searchParams.set(VIEW_PARAM, viewSlug(id, name));

    return url;
}

/**
 * The same as [viewUrl], as the value of an `href`: path and query, without the origin -- what a
 * link in the app is written with.
 */
export function viewHref(id: string, name: string | null | undefined, current: URL): string {
    const url = viewUrl(id, name, current);

    return `${url.pathname}${url.search}`;
}
