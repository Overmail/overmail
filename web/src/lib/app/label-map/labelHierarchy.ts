import type {LabelMapLabel} from "$lib/app/label-map/LabelMapSimulation";

/**
 * How large a share of a label's mails another label has to be on to take it under its wing.
 *
 * Tuned by eye: lower folds more labels away behind fewer, higher leaves more of them standing on
 * their own.
 */
export const PARENT_SHARE = 0.6;

/**
 * The labels as layers: which one a label is folded away behind, and which are folded behind it.
 *
 * A label belongs under a larger one that most of its mails carry as well -- a shop's label under
 * "Newsletter". Such a broad label is on many mails and related to a lot, and showing everything
 * it is related to at once is what buries the map; folded, its labels come out one layer at a
 * time.
 */
export type LabelHierarchy = {
    /** The label each folded label belongs under. A label without one is a root. */
    parentOf: Map<string, string>;
    /** The labels directly under each label, largest first. */
    childrenOf: Map<string, string[]>;
    /** What the map starts with: every label that belongs under no other. */
    roots: string[];
};

/**
 * Puts each label under the larger label that most of its mails carry too, if that is at least
 * [minShare] of them.
 *
 * Only a label on more mails can be a parent, so following parents always ends and the layers are
 * a forest. Two labels on as many mails stay side by side: which one went under the other would
 * be down to their ids, and a chain of labels on one mail each is a layer per click for nothing.
 */
export function buildHierarchy(labels: readonly LabelMapLabel[], minShare = PARENT_SHARE): LabelHierarchy {
    const byId = new Map(labels.map((label) => [label.id, label]));
    const parentOf = new Map<string, string>();
    const childrenOf = new Map<string, string[]>();

    for (const label of labels) {
        if (label.email_count === 0) continue;

        let best: {id: string; share: number; count: number} | null = null;
        for (const relation of label.relations) {
            const other = byId.get(relation.label_id);
            if (!other || other.email_count <= label.email_count) continue;

            const share = relation.count / label.email_count;
            if (share < minShare) continue;
            // The label sharing the most; between equals the narrower one, which is the layer
            // closer to this label -- Prime under Amazon, not beside it under Newsletter.
            if (best === null || share > best.share || (share === best.share && other.email_count < best.count)) {
                best = {id: other.id, share, count: other.email_count};
            }
        }

        if (best !== null) {
            parentOf.set(label.id, best.id);
            const siblings = childrenOf.get(best.id) ?? [];
            siblings.push(label.id);
            childrenOf.set(best.id, siblings);
        }
    }

    for (const children of childrenOf.values()) {
        children.sort((a, b) => byId.get(b)!.email_count - byId.get(a)!.email_count);
    }

    return {
        parentOf,
        childrenOf,
        roots: labels.filter((label) => !parentOf.has(label.id)).map((label) => label.id),
    };
}

/** What is on the map: the roots, and under every unfolded label that is on it, its children. */
export function visibleLabels(hierarchy: LabelHierarchy, expanded: ReadonlySet<string>): Set<string> {
    const visible = new Set<string>();
    const pending = [...hierarchy.roots];

    while (pending.length > 0) {
        const id = pending.pop()!;
        visible.add(id);
        if (expanded.has(id)) pending.push(...(hierarchy.childrenOf.get(id) ?? []));
    }

    return visible;
}

/**
 * [expanded] after a click on [id]: a folded label opens one layer, an open one folds away with
 * everything opened under it, so opening it again shows one layer and not the whole way down.
 */
export function toggleLabel(hierarchy: LabelHierarchy, expanded: ReadonlySet<string>, id: string): Set<string> {
    const next = new Set(expanded);

    if (!next.has(id)) {
        next.add(id);
        return next;
    }

    const pending = [id];
    while (pending.length > 0) {
        const current = pending.pop()!;
        next.delete(current);
        pending.push(...(hierarchy.childrenOf.get(current) ?? []));
    }

    return next;
}

/**
 * What stays in sight while [id] is in focus: the label, the labels it belongs under all the way
 * up, those under it, and every label that shares a mail with it. Everything else fades.
 */
export function relatedLabels(
    hierarchy: LabelHierarchy,
    labels: ReadonlyMap<string, LabelMapLabel>,
    id: string,
): Set<string> {
    const related = new Set([id]);

    for (let parent = hierarchy.parentOf.get(id); parent !== undefined; parent = hierarchy.parentOf.get(parent)) {
        related.add(parent);
    }
    for (const child of hierarchy.childrenOf.get(id) ?? []) related.add(child);
    for (const relation of labels.get(id)?.relations ?? []) related.add(relation.label_id);

    return related;
}
