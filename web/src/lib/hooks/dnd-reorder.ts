/**
 * The order math behind a drag, on ids alone: no dom, no items, no reactivity -- see
 * `dnd-reorder.svelte.ts` for the part that has all three.
 *
 * Ids rather than the items themselves is what makes a drag survive the list changing under it:
 * a socket that renames an item, adds one or takes one away writes to the items, and the drag
 * only ever says in which order and in which zone their ids sit.
 */

/** Which ids sit in which zone, in which order. */
export type DndOrder = Record<string, string[]>;

/** A place in the order: the zone and the index within it. */
export type DndSpot = {zone: string; index: number};

/**
 * One id on its way from [from] to [to].
 *
 * [to].index is counted among the *other* ids of the target zone -- the dragged one is taken out
 * before it is put back in. That is also what a slot from [slotFor] means, so the two fit
 * together without an off-by-one.
 */
export type DndMove = {id: string; from: DndSpot; to: DndSpot};

/** Where [id] sits in [order], or null when no zone holds it. */
export function locate(order: DndOrder, id: string): DndSpot | null {
    for (const [zone, ids] of Object.entries(order)) {
        const index = ids.indexOf(id);
        if (index !== -1) return {zone, index};
    }

    return null;
}

/**
 * [order] with [move] carried out: the id leaves wherever it was and goes back in at
 * `move.to`. The default for `applyMove`, and the building block a custom one starts from.
 *
 * An index past the end of the target zone lands at its end rather than nowhere, which is what
 * a cursor below the last row asks for.
 */
export function moveTo(order: DndOrder, move: DndMove): DndOrder {
    if (!(move.to.zone in order)) return order;

    const next: DndOrder = {};
    for (const [zone, ids] of Object.entries(order)) next[zone] = ids.filter((id) => id !== move.id);

    const target = next[move.to.zone];
    target.splice(Math.max(0, Math.min(move.to.index, target.length)), 0, move.id);

    return next;
}

/**
 * How many of [rects] have their midpoint before [position] -- the slot the cursor is asking
 * for, counted among the rows it is not one of (the dragged row is left out by the caller).
 *
 * Monotonic in [position], so two neighbours agree on their shared edge and the list does not
 * flicker there. Once the row has moved the cursor sits over it, which is a full row of
 * hysteresis.
 */
export function slotFor(rects: {start: number; size: number}[], position: number): number {
    let slot = 0;
    for (const rect of rects) if (position > rect.start + rect.size / 2) slot++;

    return slot;
}

/** Whether both orders hold the same ids in the same zones in the same order. */
export function sameOrder(a: DndOrder, b: DndOrder): boolean {
    const zones = Object.keys(a);
    if (zones.length !== Object.keys(b).length) return false;

    return zones.every((zone) => {
        const one = a[zone];
        const other = b[zone];

        return other !== undefined && one.length === other.length && one.every((id, at) => id === other[at]);
    });
}

/**
 * The items of [live], handed out in the order [order] puts their ids in -- what is rendered
 * while a drag is on.
 *
 * An item [order] says nothing about has arrived since the drag started: it goes to the end of
 * the zone [live] has it in, rather than being left out of the list until the drag is over. An
 * id [live] no longer has anything for is dropped; the item behind it is gone.
 *
 * The zones are the ones [live] has. A zone only [order] knows is not rendered by anyone.
 */
export function project<T>(
    order: DndOrder,
    live: Record<string, T[]>,
    id: (item: T) => string,
): Record<string, T[]> {
    const items = new Map<string, T>();
    for (const zone of Object.values(live)) for (const item of zone) items.set(id(item), item);

    const placed = new Set(Object.values(order).flat());
    const projected: Record<string, T[]> = {};

    for (const [zone, live_] of Object.entries(live)) {
        const known = (order[zone] ?? [])
            .map((itemId) => items.get(itemId))
            .filter((item) => item !== undefined);

        projected[zone] = [...known, ...live_.filter((item) => !placed.has(id(item)))];
    }

    return projected;
}
