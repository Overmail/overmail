import {expect, mock, test} from "bun:test";
import {ShareDialogViewModel} from "./ShareDialogViewModel.svelte";
import type {Share, ShareDraft, ShareRepository} from "$lib/repository/ShareRepository";

const MAIL = "11111111-2222-3333-4444-555555555555";

/** Fixed, so "in seven days" is something a test can name. 2026-03-01T12:00:00Z. */
const NOW = Date.UTC(2026, 2, 1, 12, 0, 0);
const NOW_SECONDS = Math.floor(NOW / 1000);
const DAY = 24 * 60 * 60;

const SHARE: Share = {
    id: "s-1",
    shareName: "Projektgruppe",
    sharedAt: NOW_SECONDS,
    validUntil: NOW_SECONDS + 7 * DAY,
    includeLabels: true,
    hasPassword: true,
    allowMetadataWithoutPassword: true,
};

/** A dialog whose repository the test controls. */
function opened(overrides: Partial<ShareRepository> = {}, shares: Share[] = []) {
    const repository = {
        list: mock(async () => shares),
        create: mock(async (_mail: string, draft: ShareDraft) => ({...SHARE, id: "s-new"})),
        update: mock(async (_mail: string, _id: string, draft: ShareDraft) => SHARE),
        remove: mock(async () => {}),
        ...overrides,
    } as unknown as ShareRepository;

    return {viewModel: new ShareDialogViewModel(MAIL, repository, () => NOW), repository};
}

test("a new link lasts a week unless something else is picked", async () => {
    const {viewModel, repository} = opened();

    expect(viewModel.expiry).toBe("7d");
    expect(viewModel.validUntil).toBe(NOW_SECONDS + 7 * DAY);

    await viewModel.submit();
    expect((repository.create as any).mock.calls[0][1].validUntil).toBe(NOW_SECONDS + 7 * DAY);
});

test("a link that does not run out sends no date at all", async () => {
    const {viewModel, repository} = opened();
    viewModel.expiry = "never";

    expect(viewModel.validUntil).toBeNull();
    await viewModel.submit();
    expect((repository.create as any).mock.calls[0][1].validUntil).toBeNull();
});

test("a picked day lasts to the end of it, in the reader's own timezone", () => {
    const {viewModel} = opened();
    viewModel.expiry = "custom";
    viewModel.expiresOn = "2026-04-01";

    const end = new Date(2026, 3, 1, 23, 59, 59);
    expect(viewModel.validUntil).toBe(Math.floor(end.getTime() / 1000));
});

test("a day that is missing or already gone is refused before the request is made", async () => {
    const {viewModel, repository} = opened();
    viewModel.expiry = "custom";

    expect(viewModel.problem).toBe("expiry-missing");
    expect(viewModel.canSubmit).toBe(false);

    viewModel.expiresOn = "2026-02-01";
    expect(viewModel.problem).toBe("expiry-past");

    expect(await viewModel.submit()).toBeNull();
    expect((repository.create as any).mock.calls.length).toBe(0);

    viewModel.expiresOn = "2026-04-01";
    expect(viewModel.problem).toBeNull();
});

test("a password nobody could type is refused, an empty field is no password", async () => {
    const {viewModel, repository} = opened();

    viewModel.password = "ab";
    expect(viewModel.problem).toBe("password-too-short");
    expect(await viewModel.submit()).toBeNull();

    viewModel.password = "";
    expect(viewModel.canSubmit).toBe(true);
    await viewModel.submit();
    expect((repository.create as any).mock.calls[0][1].password).toBeNull();
});

test("editing opens on the share, and leaves its password alone", async () => {
    const {viewModel, repository} = opened({}, [SHARE]);
    viewModel.startEdit(SHARE);

    expect(viewModel.isEditing).toBe(true);
    expect(viewModel.shareName).toBe("Projektgruppe");
    expect(viewModel.includeLabels).toBe(true);
    // Never handed out, so there is nothing to put in the field -- and the empty field is what
    // says "as it was".
    expect(viewModel.password).toBe("");
    expect(viewModel.replacingPassword).toBe(true);
    expect(viewModel.expiry).toBe("custom");

    await viewModel.submit();
    const [mail, id, draft] = (repository.update as any).mock.calls[0];
    expect(mail).toBe(MAIL);
    expect(id).toBe("s-1");
    expect(draft.password).toBeNull();
    expect(draft.removePassword).toBe(false);
});

test("taking the password off says so, and a typed one wins over it", async () => {
    const {viewModel, repository} = opened({}, [SHARE]);

    viewModel.startEdit(SHARE);
    viewModel.removePassword = true;
    await viewModel.submit();
    expect((repository.update as any).mock.calls[0][2].removePassword).toBe(true);

    viewModel.startEdit(SHARE);
    viewModel.removePassword = true;
    viewModel.password = "neues Passwort";
    await viewModel.submit();
    const draft = (repository.update as any).mock.calls[1][2];
    expect(draft.password).toBe("neues Passwort");
    // Two answers to one question otherwise: the new password is the one that counts.
    expect(draft.removePassword).toBe(false);
});

test("a written link empties the form and the list is read again", async () => {
    const {viewModel, repository} = opened({}, [SHARE]);
    viewModel.shareName = "Projektgruppe";
    viewModel.password = "hunter2";

    expect(await viewModel.submit()).not.toBeNull();
    expect(viewModel.isEditing).toBe(false);
    expect(viewModel.shareName).toBe("");
    expect(viewModel.password).toBe("");
    expect(viewModel.shares).toEqual([SHARE]);
    expect((repository.list as any).mock.calls.length).toBe(1);
});

test("a write that failed leaves the form standing with what was typed in it", async () => {
    const {viewModel} = opened({create: mock(async () => Promise.reject(new Error("nope")))});
    viewModel.shareName = "Projektgruppe";

    expect(await viewModel.submit()).toBeNull();
    expect(viewModel.saveState).toEqual({type: "failed"});
    expect(viewModel.shareName).toBe("Projektgruppe");
});

test("a list that could not be read says so rather than showing no links", async () => {
    const {viewModel} = opened({list: mock(async () => Promise.reject(new Error("nope")))});

    await viewModel.load();
    expect(viewModel.listState).toEqual({type: "failed"});
});

test("deleting the link that is being edited puts the form back on a new one", async () => {
    const {viewModel, repository} = opened({}, [SHARE]);
    viewModel.startEdit(SHARE);
    viewModel.askToDelete(SHARE);

    expect(await viewModel.confirmDelete()).toBe(true);
    expect((repository.remove as any).mock.calls[0]).toEqual([MAIL, "s-1"]);
    expect(viewModel.isEditing).toBe(false);
    expect(viewModel.deleting).toBeNull();
});

test("a delete that failed keeps the confirmation open", async () => {
    const {viewModel} = opened({remove: mock(async () => Promise.reject(new Error("nope")))}, [SHARE]);
    viewModel.askToDelete(SHARE);

    expect(await viewModel.confirmDelete()).toBe(false);
    // `toEqual`, not `toBe`: reactive state hands back a proxy of what was put in it.
    expect(viewModel.deleting).toEqual(SHARE);
    expect(viewModel.deleteFailed).toBe(true);
});
