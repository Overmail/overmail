import {browser} from "$app/environment";
import {SessionValue} from "$lib/hooks/session-value.svelte";

/** Narrow enough to leave the page next to it usable, wide enough for a message and the composer. */
const MIN_WIDTH = 360;

/** What the panel is worth on a tab that has never been dragged -- the 36rem it shipped with. */
const DEFAULT_WIDTH = 576;

/** The share of the window the panel may take at most, whatever was dragged out before. */
const MAX_WIDTH_FRACTION = 0.8;

/**
 * Whether the assistant panel is open and how wide it is. Both are remembered per tab: a reload
 * lands on the same panel it was closed with, a second window can differ.
 */
export class SidePanelState {
    private readonly openValue = new SessionValue<boolean>(
        "overmail.panel.open",
        false,
        (stored) => typeof stored === "boolean" ? stored : null,
    );

    private readonly widthValue = new SessionValue<number>(
        "overmail.panel.width",
        DEFAULT_WIDTH,
        (stored) => typeof stored === "number" && Number.isFinite(stored) ? stored : null,
    );

    /** A drag is in progress: whoever animates its width has to stay out of the way until it ends. */
    isResizing: boolean = $state(false);

    get open(): boolean {
        return this.openValue.current;
    }

    set open(next: boolean) {
        this.openValue.current = next;
    }

    /**
     * Clamped on the way out as well as in: the window can be a good deal narrower than the one
     * the width was dragged out on, and a stored value is not re-clamped by anything else.
     */
    get width(): number {
        return this.clamp(this.widthValue.current);
    }

    set width(next: number) {
        this.widthValue.current = this.clamp(next);
    }

    /**
     * Puts the bounds back on the current width, for when the window changed rather than the
     * panel: a width dragged out on a wide screen would otherwise still be in force on a narrow
     * one, and nothing else re-reads it.
     */
    reclamp() {
        this.width = this.widthValue.current;
    }

    get minWidth(): number {
        return MIN_WIDTH;
    }

    get maxWidth(): number {
        return Math.max(MIN_WIDTH, window.innerWidth * MAX_WIDTH_FRACTION);
    }

    private clamp(width: number): number {
        // There is no window to measure against on the server, and no panel rendered there
        // either -- the value passes through so the first client render matches the fallback.
        if (!browser) return width;

        return Math.min(Math.max(width, MIN_WIDTH), this.maxWidth);
    }
}
