import {getContext, setContext, type Snippet} from "svelte";

const key = Symbol("page-header");

/**
 * How something laid over the bar's inline end gets where it goes.
 *
 * The controls that step out from under it run this very function for this many milliseconds,
 * rather than a CSS curve that merely looks like it -- see [coverHeaderEnd].
 */
export type CoverMotion = {
    durationMs: number;
    easing: (t: number) => number;
};

/** Something the page can lay over the bar's inline end; see [coverHeaderEnd]. */
export type Cover = {
    /** How much of that end it takes, in pixels. */
    width: number;
    motion: CoverMotion;
};

/**
 * A [Cover] and whether it is there right now.
 *
 * Two fields rather than a width that drops to zero: the bar makes room for the cover with a
 * transition of its own, and a transition going out reads what it is given at that moment -- a
 * zero there and the room would vanish in one frame instead of closing with the panel.
 */
export type HeaderCover = Cover & {present: boolean};

/** Nothing over the bar, and nothing that ever moved there. */
const UNCOVERED: HeaderCover = {
    width: 0,
    motion: {durationMs: 0, easing: (t) => t},
    present: false,
};

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
    /**
     * What the page has laid over the bar's inline end.
     *
     * The mail panel is fixed and pinned to that end, so it paints over the bar rather than
     * pushing it aside, and the controls under it would be unreachable. They step in by its
     * width instead, along its movement.
     */
    coveredEnd: HeaderCover;
};

/** Called by the layout: it draws the header and is what the pages render inside. */
export function createPageHeader(): PageHeader {
    const header: PageHeader = $state({actions: null, restoreFocus: null, coveredEnd: UNCOVERED});
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

/**
 * Says that the page lays [cover] over the bar's inline end whenever [present] says so, for as
 * long as this component is mounted.
 *
 * A getter, so this can be called from whatever opens and closes the cover rather than from the
 * cover itself: the bar then starts to make room in the same frame the panel starts to slide,
 * instead of once the panel has already gone.
 */
export function coverHeaderEnd(cover: Cover, present: () => boolean) {
    const header = getContext<PageHeader | undefined>(key);
    if (!header) return;

    $effect(() => {
        header.coveredEnd = {...cover, present: present()};

        return () => (header.coveredEnd = {...cover, present: false});
    });
}
