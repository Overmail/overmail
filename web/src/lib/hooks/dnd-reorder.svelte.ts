import {tick, type Component} from "svelte";
import type {Attachment} from "svelte/attachments";
import {
    locate,
    moveTo,
    project,
    sameOrder,
    slotFor,
    type DndMove,
    type DndOrder,
    type DndSpot,
} from "./dnd-reorder";

/** Long enough to follow a row to its new place, short enough not to hold up the next drag. */
const DEFAULT_DURATION = 150;

/** A 1x1 transparent gif. */
const TRANSPARENT_PIXEL = "data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7";

/**
 * What the browser drags along under the cursor when nothing should be: the row itself is
 * already moving through the list, and a ghost of it beside the cursor says the same thing a
 * second time.
 *
 * Built once, not per drag: Firefox drags nothing at all when the image it is handed has not
 * been decoded by the time the drag starts. Null while rendering on the server, where there is
 * no `Image`.
 */
const emptyDragImage = (() => {
    if (typeof Image === "undefined") return null;

    const image = new Image();
    image.src = TRANSPARENT_PIXEL;

    return image;
})();

/** What stands in for the dragged item while it is in flight. */
export type DndGhost = {
    /**
     * Whether the row stays in the list at the place the drag has put it -- the default, and
     * what lets the list itself be the answer to where the item would land. False takes it out
     * of the list, which only makes sense together with [cursor].
     *
     * A component renders in place of the row's own content, at that same place.
     */
    list?: boolean | Component<{id: string}>;
    /**
     * Whether the browser hangs the usual picture of the row under the cursor. Off by default:
     * with [list] the row is already visible where it counts.
     */
    cursor?: boolean;
};

export type DndReorderOptions<T> = {
    /**
     * The items per zone. A function, so the drag sees what arrives while it lasts -- a plain
     * object is read once and then stands still.
     */
    zones: Record<string, T[]> | (() => Record<string, T[]>);
    /** What identifies an item; everything but the rendering runs on this alone. */
    id: (item: T) => string;
    /** Which way the rows are stacked. */
    axis?: "x" | "y";
    /** How long a row takes to its new place, in ms. */
    duration?: number;
    ghost?: DndGhost;
    /**
     * What a move does to the order, for zones with a rule of their own -- a limit on how many
     * one holds, a kind it will not take. Returning null turns the move down, and the list stays
     * as it is.
     *
     * Defaults to [moveTo]: out where it was, in where the cursor says.
     */
    applyMove?: (order: DndOrder, move: DndMove) => DndOrder | null;
    /**
     * The drop, which is the save: what the list reads as now is what this is told. The ghost
     * order is held until it is done, so a slow answer does not snap the row back first.
     *
     * Not called for a drop that changed nothing.
     */
    onDrop?: (move: DndMove & {order: DndOrder}) => unknown;
};

/**
 * A list -- or several, which items travel between -- reordered by dragging.
 *
 * The zones are read, never written: this holds the drag's own order while one is on, and hands
 * out the items through it ([zones]). The drop is where the caller is told, and where its own
 * state catches up. Nothing else moves an item.
 *
 * Rows are followed to their new place by measuring them before and after the order changes and
 * animating the difference, rather than by `animate:flip`, which only works on a direct child of
 * a keyed each block and therefore not from inside a component. The same measurement carries a
 * row from one zone into another, where flip could not follow at all.
 *
 * Reordering is pointer-only for now; [move] is what a keyboard would go through.
 */
export class DndReorder<T> {
    readonly #options: DndReorderOptions<T>;

    /** The rendered element per id, for measuring and for animating. */
    readonly #elements = new Map<string, HTMLElement>();
    readonly #animations = new Map<string, Animation>();

    /** The drag's order, or null when nothing is being dragged. */
    #order = $state<DndOrder | null>(null);
    #dragging = $state<string | null>(null);

    /** Where the dragged id started out, which is what the drop reports as `from`. */
    #origin: DndSpot | null = null;

    /**
     * When the rows have finished moving. Mid-flight `getBoundingClientRect` reports positions
     * in transit, and a slot computed from those would put the row somewhere else again.
     */
    #settledAt = 0;

    readonly #live = $derived.by(() => {
        const zones = this.#options.zones;

        return typeof zones === "function" ? zones() : zones;
    });

    readonly #liveOrder = $derived.by(() => {
        const order: DndOrder = {};
        for (const [zone, items] of Object.entries(this.#live)) order[zone] = items.map(this.#options.id);

        return order;
    });

    constructor(options: DndReorderOptions<T>) {
        this.#options = options;
    }

    /** What is rendered: the drag's order while there is one, the caller's otherwise. */
    get zones(): Record<string, T[]> {
        const order = this.#order;
        if (order === null) return this.#live;

        return project(order, this.#live, this.#options.id);
    }

    /** The id being dragged, or null. */
    get draggingId(): string | null {
        return this.#dragging;
    }

    isDragging(id: string): boolean {
        return this.#dragging === id;
    }

    /** What renders in place of the dragged row's content, if the caller asked for one. */
    get ghostComponent(): Component<{id: string}> | null {
        const list = this.#options.ghost?.list;

        return typeof list === "function" ? list : null;
    }

    /** Registers the element of [id] for measuring. Used by `DndReorderElement`. */
    attach(id: string): Attachment<HTMLElement> {
        return (node) => {
            this.#elements.set(id, node);

            // Only when it is still this element's: moving between zones mounts the new one
            // before the old one is torn down.
            return () => {
                if (this.#elements.get(id) !== node) return;
                this.#elements.delete(id);
                this.#animations.delete(id);
            };
        };
    }

    /** What the element holding the items of [zone] listens for. */
    zoneProps(zone: string) {
        return {
            "data-dnd-zone": zone,
            ondragover: (event: DragEvent & {currentTarget: HTMLElement}) => this.#onDragOver(event, zone),
            ondrop: (event: DragEvent) => void this.#onDrop(event),
        };
    }

    /**
     * What a row carries so it can be found, measured and dimmed.
     *
     * `ghost.list: false` takes the row out of the flow rather than out of the dom: an element
     * the drag started on and that is then unmounted fires no `dragend`, and the drag would
     * have no end. `style` is therefore this one's, not the caller's.
     */
    itemProps(id: string) {
        const hidden = this.#dragging === id && this.#options.ghost?.list === false;

        return {
            "data-dnd-item": id,
            "data-dnd-dragging": this.#dragging === id ? "" : undefined,
            style: hidden ? "display:none" : undefined,
            ondragend: () => this.#onDragEnd(),
        };
    }

    /** What starts a drag -- the row itself, or a grip inside it. */
    handleProps(id: string) {
        return {
            draggable: true,
            ondragstart: (event: DragEvent) => this.#onDragStart(event, id),
        };
    }

    /**
     * Puts [id] at [index] of [zone], counted among the ids it is not one of. The one way the
     * order changes, whatever asked for it.
     */
    async move(id: string, zone: string, index: number): Promise<void> {
        const order = this.#order ?? this.#liveOrder;
        const from = locate(order, id);
        if (from === null) return;

        const next = (this.#options.applyMove ?? moveTo)(order, {id, from, to: {zone, index}});
        // Turned down, or the same list rebuilt.
        if (next === null || sameOrder(next, order)) return;

        await this.#reorder(next);
    }

    #onDragStart(event: DragEvent, id: string) {
        const origin = locate(this.#liveOrder, id);
        if (origin === null) return;

        this.#dragging = id;
        this.#origin = origin;
        this.#order = this.#liveOrder;
        this.#settledAt = 0;

        if (!event.dataTransfer) return;
        // Firefox starts no drag without a payload.
        event.dataTransfer.setData("text/plain", id);
        event.dataTransfer.effectAllowed = "move";
        if (this.#options.ghost?.cursor !== true && emptyDragImage !== null) {
            event.dataTransfer.setDragImage(emptyDragImage, 0, 0);
        }
    }

    /**
     * Listened for on the zone rather than on each row, so the gaps between rows and an emptied
     * zone are covered as well.
     */
    #onDragOver(event: DragEvent & {currentTarget: HTMLElement}, zone: string) {
        const dragging = this.#dragging;
        // Something else is being dragged -- a file, a link. Not ours to take.
        if (dragging === null) return;

        // Without this the browser refuses the drop and the row springs back.
        event.preventDefault();
        if (event.dataTransfer) event.dataTransfer.dropEffect = "move";

        if (performance.now() < this.#settledAt) return;

        const horizontal = this.#options.axis === "x";
        const rects: {start: number; size: number}[] = [];

        for (const element of event.currentTarget.querySelectorAll<HTMLElement>("[data-dnd-item]")) {
            if (element.dataset.dndItem === dragging) continue;
            const rect = element.getBoundingClientRect();
            rects.push(horizontal ? {start: rect.left, size: rect.width} : {start: rect.top, size: rect.height});
        }

        void this.move(dragging, zone, slotFor(rects, horizontal ? event.clientX : event.clientY));
    }

    async #onDrop(event: DragEvent) {
        const id = this.#dragging;
        const order = this.#order;
        const from = this.#origin;
        if (id === null) return;

        event.preventDefault();
        // Cleared before the await, so the dragend that follows knows the drop has it.
        this.#dragging = null;

        const to = order === null ? null : locate(order, id);
        // Dropped where it already was: nothing to write.
        if (order === null || from === null || to === null || sameOrder(order, this.#liveOrder)) {
            await this.#clear();
            return;
        }

        try {
            await this.#options.onDrop?.({id, from, to, order});
        } catch (error) {
            console.error(error);
        } finally {
            await this.#clear();
        }
    }

    /** A drag that ended anywhere else: the caller's order is what the list goes back to. */
    #onDragEnd() {
        if (this.#dragging === null) return;

        this.#dragging = null;
        void this.#clear();
    }

    /** Lets go of the drag's order, following the rows back to where the caller has them. */
    async #clear() {
        if (this.#order === null) return;

        await this.#reorder(null);
    }

    /** Puts [next] on screen and moves every row that ends up somewhere else along with it. */
    async #reorder(next: DndOrder | null) {
        const duration = this.#options.duration ?? DEFAULT_DURATION;

        const before = new Map<string, DOMRect>();
        for (const [id, element] of this.#elements) before.set(id, element.getBoundingClientRect());

        this.#order = next;
        await tick();

        for (const [id, element] of this.#elements) {
            const from = before.get(id);
            if (from === undefined) continue;

            // Cancelled first, so what is measured is where the row belongs and not where an
            // earlier move still has it.
            this.#animations.get(id)?.cancel();
            const to = element.getBoundingClientRect();
            // A row on its way in or out of the flow -- there is no distance to animate.
            if (to.width === 0 && to.height === 0) continue;
            if (from.width === 0 && from.height === 0) continue;

            const x = from.left - to.left;
            const y = from.top - to.top;
            if (x === 0 && y === 0) continue;

            this.#animations.set(
                id,
                element.animate({transform: [`translate(${x}px, ${y}px)`, "none"]}, {duration, easing: "ease-out"}),
            );
        }

        this.#settledAt = performance.now() + duration;
    }
}
