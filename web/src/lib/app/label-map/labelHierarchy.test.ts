import {expect, test} from "bun:test";
import {buildHierarchy, relatedLabels, toggleLabel, visibleLabels} from "./labelHierarchy";
import type {LabelMapLabel} from "./LabelMapSimulation";

function label(id: string, emailCount: number, relations: Record<string, number> = {}): LabelMapLabel {
    return {
        id,
        name: id,
        color: "#000000",
        email_count: emailCount,
        relations: Object.entries(relations).map(([labelId, count]) => ({label_id: labelId, count})),
    };
}

/**
 * Newsletter is on 100 mails; Amazon's 20 all carry it, and Prime's 5 all carry Amazon (and so
 * Newsletter). Uni and HPI overlap, but only on a third of HPI's mails.
 */
const labels = [
    label("newsletter", 100, {amazon: 20, prime: 5, spiegel: 15}),
    label("amazon", 20, {newsletter: 20, prime: 5}),
    label("prime", 5, {newsletter: 5, amazon: 5}),
    label("spiegel", 15, {newsletter: 15}),
    label("uni", 40, {hpi: 10}),
    label("hpi", 30, {uni: 10}),
];

test("a label goes under the larger label most of its mails carry too", () => {
    const hierarchy = buildHierarchy(labels);

    expect(hierarchy.parentOf.get("amazon")).toBe("newsletter");
    expect(hierarchy.parentOf.get("spiegel")).toBe("newsletter");
    expect(hierarchy.childrenOf.get("newsletter")).toEqual(["amazon", "spiegel"]);
});

test("between parents sharing as much, a label goes under the narrower one", () => {
    // Prime's mails are all on Amazon and all on Newsletter: Amazon is the layer closer to it.
    expect(buildHierarchy(labels).parentOf.get("prime")).toBe("amazon");
    expect(buildHierarchy(labels).childrenOf.get("amazon")).toEqual(["prime"]);
});

test("a label sharing too little with any other stays a root", () => {
    const hierarchy = buildHierarchy(labels);

    expect(hierarchy.parentOf.has("hpi")).toBe(false);
    expect(hierarchy.roots).toEqual(["newsletter", "uni", "hpi"]);
});

test("labels on as many mails stay side by side, under the larger one they share", () => {
    const hierarchy = buildHierarchy([
        label("newsletter", 20, {a: 1, b: 1}),
        label("a", 1, {newsletter: 1, b: 1}),
        label("b", 1, {newsletter: 1, a: 1}),
    ]);

    expect(hierarchy.parentOf.get("a")).toBe("newsletter");
    expect(hierarchy.parentOf.get("b")).toBe("newsletter");
});

test("two labels always together are both roots when nothing larger takes them", () => {
    const hierarchy = buildHierarchy([label("a", 10, {b: 10}), label("b", 10, {a: 10})]);

    expect(hierarchy.roots).toEqual(["a", "b"]);
});

test("the map starts with the roots, and a click opens one layer", () => {
    const hierarchy = buildHierarchy([
        label("newsletter", 100, {amazon: 20}),
        label("amazon", 20, {newsletter: 20, prime: 5}),
        label("prime", 5, {amazon: 5}),
    ]);

    expect(visibleLabels(hierarchy, new Set())).toEqual(new Set(["newsletter"]));

    const opened = toggleLabel(hierarchy, new Set(), "newsletter");
    expect(visibleLabels(hierarchy, opened)).toEqual(new Set(["newsletter", "amazon"]));

    const deeper = toggleLabel(hierarchy, opened, "amazon");
    expect(visibleLabels(hierarchy, deeper)).toEqual(new Set(["newsletter", "amazon", "prime"]));
});

test("folding a label folds everything opened under it", () => {
    const hierarchy = buildHierarchy([
        label("newsletter", 100, {amazon: 20}),
        label("amazon", 20, {newsletter: 20, prime: 5}),
        label("prime", 5, {amazon: 5}),
    ]);

    const folded = toggleLabel(hierarchy, new Set(["newsletter", "amazon"]), "newsletter");

    expect(folded.size).toBe(0);
    // Opening it again shows one layer, not the whole way down.
    expect(visibleLabels(hierarchy, toggleLabel(hierarchy, folded, "newsletter"))).toEqual(
        new Set(["newsletter", "amazon"])
    );
});

test("in focus, a label keeps its parents, its children and what it shares mails with", () => {
    // Relations come from the server both ways round.
    const all = [
        ...labels.filter((each) => each.id !== "amazon"),
        label("amazon", 20, {newsletter: 20, prime: 5, "prime-video": 2}),
        label("unrelated", 3),
        label("prime-video", 2, {prime: 2, amazon: 2, newsletter: 2}),
    ];
    const hierarchy = buildHierarchy(all);
    const byId = new Map(all.map((each) => [each.id, each]));

    const related = relatedLabels(hierarchy, byId, "amazon");

    expect(related).toEqual(new Set(["amazon", "newsletter", "prime", "prime-video"]));
    expect(related.has("unrelated")).toBe(false);
    expect(related.has("spiegel")).toBe(false);
});
