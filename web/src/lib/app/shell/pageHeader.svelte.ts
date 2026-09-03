import {getContext, setContext, type Snippet} from "svelte";

const key = Symbol("page-header");

/** What the open page contributes to the header the layout draws for it. */
export type PageHeader = {
    /** Controls for the right end of the bar, before the assistant. */
    actions: Snippet | null;
    /**
     * Where the keyboard goes once something opened from the header closes. A page that is
     * worked through by keyboard takes it back; without one it stays on the trigger, which
     * answers to Space itself.
     */
    restoreFocus: (() => void) | null;
};

/** Called by the layout: it draws the header and is what the pages render inside. */
export function createPageHeader(): PageHeader {
    const header: PageHeader = $state({actions: null, restoreFocus: null});
    setContext(key, header);
    return header;
}

/**
 * Hands the header what this page adds to it, and takes it back when the page goes away.
 *
 * The registration runs from an effect rather than from setup: the header is a sibling that has
 * already rendered by the time a page is set up, and writing to its state mid-render is not
 * allowed.
 */
export function setPageHeader(contribution: Partial<PageHeader>) {
    const header = getContext<PageHeader | undefined>(key);
    if (!header) return;

    $effect(() => {
        Object.assign(header, contribution);

        return () => Object.assign(header, {actions: null, restoreFocus: null});
    });
}
