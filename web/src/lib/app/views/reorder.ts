/**
 * Which view another one sits behind in [order], or null when it is the first -- what the api is
 * told after a drag.
 *
 * `PATCH /api/users/me/views/{id}` asks for a neighbour rather than for a position, and a
 * neighbour is what survives a list that changed under the drag; a position would not. Null for a
 * view that is not in [order] at all: there is nothing in front of it there either.
 */
export function neighbourInOrder(order: string[], viewId: string): string | null {
    const at = order.indexOf(viewId);

    return at > 0 ? order[at - 1] : null;
}

/**
 * [order] with [draggedId] moved behind [afterId] -- at the front when that is null.
 *
 * What the list looks like the moment the row is let go, before the server has answered. The
 * socket sends the real order a moment later; this is only what keeps the row from jumping back
 * to where it was dragged from in between.
 */
export function reordered(order: string[], draggedId: string, afterId: string | null): string[] {
    const rest = order.filter((id) => id !== draggedId);
    const at = afterId === null ? 0 : rest.indexOf(afterId) + 1;

    // An unknown neighbour would splice at 0 and silently move the row to the top instead.
    if (afterId !== null && at === 0) return order;

    return [...rest.slice(0, at), draggedId, ...rest.slice(at)];
}
