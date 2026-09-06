import {expect, mock, test} from "bun:test";
import {ShareDialogViewModel} from "./ShareDialogViewModel.svelte";
import type {Share, ShareRepository} from "$lib/repository/ShareRepository";

const MAIL = "11111111-2222-3333-4444-555555555555";

const SHARE: Share = {
    id: "s-1",
    shareName: "Projektgruppe",
    sharedAt: 1772000000,
    validUntil: null,
    includeLabels: true,
    hasPassword: true,
    allowMetadataWithoutPassword: true,
};

/** The checkmark, short enough that a test waits it out rather than mocking the clock. */
const FLASH_MS = 5;

/** A dialog whose repository the test controls. */
function opened(overrides: Partial<ShareRepository> = {}, shares: Share[] = []) {
    const repository = {
        list: mock(async () => shares),
        create: mock(async () => SHARE),
        update: mock(async () => SHARE),
        remove: mock(async () => {}),
        ...overrides,
    } as unknown as ShareRepository;

    return {viewModel: new ShareDialogViewModel(MAIL, repository, FLASH_MS), repository};
}

/** A clipboard that takes what it is given, as a permitted browser has. */
function clipboard(write: (text: string) => Promise<void> = async () => {}) {
    const spy = mock(write);
    (globalThis.navigator as any) = {clipboard: {writeText: spy}};
    return spy;
}

const tick = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

test("the links of the mail are read when the dialog opens", async () => {
    const {viewModel, repository} = opened({}, [SHARE]);

    await viewModel.load();

    expect(viewModel.shares).toEqual([SHARE]);
    expect(viewModel.listState).toEqual({type: "ready"});
    expect((repository.list as any).mock.calls[0][0]).toBe(MAIL);
});

test("a list that could not be read says so rather than showing no links", async () => {
    const {viewModel} = opened({list: mock(async () => Promise.reject(new Error("nope")))});

    await viewModel.load();
    expect(viewModel.listState).toEqual({type: "failed"});
});

test("copying marks the link that was copied, and the mark goes on its own", async () => {
    const {viewModel} = opened({}, [SHARE]);
    const writeText = clipboard();

    expect(await viewModel.copy(SHARE, "https://overmail.example/share/abc")).toBe(true);
    expect(writeText.mock.calls[0][0]).toBe("https://overmail.example/share/abc");
    expect(viewModel.copied).toBe("s-1");

    // A receipt for what just happened; one that stays would read as a state the link is in.
    await tick(FLASH_MS * 4);
    expect(viewModel.copied).toBeNull();
});

test("a clipboard that refused marks nothing", async () => {
    const {viewModel} = opened({}, [SHARE]);
    clipboard(async () => Promise.reject(new Error("denied")));

    expect(await viewModel.copy(SHARE, "https://overmail.example/share/abc")).toBe(false);
    expect(viewModel.copied).toBeNull();
});

test("deleting the link that is open for editing closes that window too", async () => {
    const {viewModel, repository} = opened({}, [SHARE]);
    viewModel.editing = SHARE;
    viewModel.askToDelete(SHARE);

    expect(await viewModel.confirmDelete()).toBe(true);
    expect((repository.remove as any).mock.calls[0]).toEqual([MAIL, "s-1"]);
    expect(viewModel.editing).toBeNull();
    expect(viewModel.deleting).toBeNull();
    // Read again, so the row is gone from the list and not only from the server.
    expect((repository.list as any).mock.calls.length).toBe(1);
});

test("a delete that failed keeps the confirmation open", async () => {
    const {viewModel} = opened({remove: mock(async () => Promise.reject(new Error("nope")))}, [SHARE]);
    viewModel.askToDelete(SHARE);

    expect(await viewModel.confirmDelete()).toBe(false);
    // `toEqual`, not `toBe`: reactive state hands back a proxy of what was put in it.
    expect(viewModel.deleting).toEqual(SHARE);
    expect(viewModel.deleteFailed).toBe(true);
});

test("a closed dialog stops its timer rather than writing into state nobody reads", async () => {
    const {viewModel} = opened({}, [SHARE]);
    clipboard();

    await viewModel.copy(SHARE, "https://overmail.example/share/abc");
    viewModel.dispose();

    await tick(FLASH_MS * 4);
    expect(viewModel.copied).toBe("s-1");
});
