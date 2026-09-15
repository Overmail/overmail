/**
 * What a `DndReorderHandle` needs from the row it sits in: the two props that start the drag.
 *
 * Only those, so the context carries no item type -- a handle is a grip and has no business
 * knowing what it is gripping.
 */
export type DndItemContext = {
    handleProps: () => {draggable: boolean; ondragstart: (event: DragEvent) => void};
};

export const DND_ITEM = Symbol("dnd-item");
