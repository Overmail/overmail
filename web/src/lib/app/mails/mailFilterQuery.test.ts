import {expect, test} from "bun:test";
import {filterKey, filterParams} from "./mailFilterQuery";
import type {ViewFilter} from "$lib/repository/ViewSocket";

const NO_FILTER: ViewFilter = {
    readState: null,
    archivedState: null,
    imapAccountIds: null,
    sentBy: null,
    sentTo: null,
    hasLabels: null,
};

test("nothing set is nothing sent", () => {
    expect(filterParams(NO_FILTER).toString()).toBe("");
});

test("a set attribute is a parameter, an empty one is an empty parameter", () => {
    expect(filterParams({...NO_FILTER, archivedState: ["Unarchive"]}).toString()).toBe(
        "archived_state=Unarchive"
    );
    // Not the same as leaving it out: this one lets nothing through.
    expect(filterParams({...NO_FILTER, hasLabels: []}).toString()).toBe("has_labels=");
});

test("false is a restriction, not an absence", () => {
    expect(filterParams({...NO_FILTER, readState: false}).toString()).toBe("read_state=false");
});

test("the same filter always spells the same key", () => {
    const one = filterKey({...NO_FILTER, hasLabels: ["b", "a"], archivedState: ["Archive", "Unarchive"]});
    const other = filterKey({...NO_FILTER, archivedState: ["Unarchive", "Archive"], hasLabels: ["a", "b"]});

    expect(one).toBe(other);
});

test("filters that ask for different mails are different keys", () => {
    expect(filterKey({...NO_FILTER, readState: true})).not.toBe(filterKey({...NO_FILTER, readState: false}));
    expect(filterKey({...NO_FILTER, hasLabels: []})).not.toBe(filterKey(NO_FILTER));
});
