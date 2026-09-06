import {expect, mock, test} from "bun:test";
import {ShareFormViewModel} from "./ShareFormViewModel.svelte";
import type {Share, ShareDraft} from "$lib/repository/ShareRepository";

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

/** A form whose write the test controls -- `create` in one dialog, `update` in the other. */
function writing(save: (draft: ShareDraft) => Promise<Share> = async () => SHARE) {
    const spy = mock(save);
    return {viewModel: new ShareFormViewModel(spy, () => NOW), save: spy};
}

/** A form opened on a share, as the edit window opens it. */
function editing(save?: (draft: ShareDraft) => Promise<Share>) {
    const {viewModel, save: spy} = writing(save);
    viewModel.reset(SHARE);
    return {viewModel, save: spy};
}

test("a new link lasts a week unless something else is picked", async () => {
    const {viewModel, save} = writing();

    expect(viewModel.expiry).toBe("7d");
    expect(viewModel.validUntil).toBe(NOW_SECONDS + 7 * DAY);

    await viewModel.submit();
    expect(save.mock.calls[0][0].validUntil).toBe(NOW_SECONDS + 7 * DAY);
});

test("a link that does not run out sends no date at all", async () => {
    const {viewModel, save} = writing();
    viewModel.expiry = "never";

    expect(viewModel.validUntil).toBeNull();
    await viewModel.submit();
    expect(save.mock.calls[0][0].validUntil).toBeNull();
});

test("a picked day lasts to the end of it, in the reader's own timezone", () => {
    const {viewModel} = writing();
    viewModel.expiry = "custom";
    viewModel.expiresOn = "2026-04-01";

    const end = new Date(2026, 3, 1, 23, 59, 59);
    expect(viewModel.validUntil).toBe(Math.floor(end.getTime() / 1000));
});

test("a day that is missing or already gone is refused before the request is made", async () => {
    const {viewModel, save} = writing();
    viewModel.expiry = "custom";

    expect(viewModel.problem).toBe("expiry-missing");
    expect(viewModel.canSubmit).toBe(false);

    viewModel.expiresOn = "2026-02-01";
    expect(viewModel.problem).toBe("expiry-past");

    expect(await viewModel.submit()).toBeNull();
    expect(save.mock.calls.length).toBe(0);

    viewModel.expiresOn = "2026-04-01";
    expect(viewModel.problem).toBeNull();
});

test("a password nobody could type is refused, an empty field is no password", async () => {
    const {viewModel, save} = writing();

    viewModel.password = "ab";
    expect(viewModel.problem).toBe("password-too-short");
    expect(await viewModel.submit()).toBeNull();

    viewModel.password = "";
    expect(viewModel.canSubmit).toBe(true);
    await viewModel.submit();
    expect(save.mock.calls[0][0].password).toBeNull();
});

test("editing opens on the share, and leaves its password alone", async () => {
    const {viewModel, save} = editing();

    expect(viewModel.isEditing).toBe(true);
    expect(viewModel.shareName).toBe("Projektgruppe");
    expect(viewModel.includeLabels).toBe(true);
    // Never handed out, so there is nothing to put in the field -- and the empty field is what
    // says "as it was".
    expect(viewModel.password).toBe("");
    expect(viewModel.replacingPassword).toBe(true);
    expect(viewModel.expiry).toBe("custom");

    await viewModel.submit();
    const draft = save.mock.calls[0][0];
    expect(draft.shareName).toBe("Projektgruppe");
    expect(draft.password).toBeNull();
    expect(draft.removePassword).toBe(false);
});

test("taking the password off says so, and a typed one wins over it", async () => {
    const {viewModel, save} = editing();

    viewModel.removePassword = true;
    await viewModel.submit();
    expect(save.mock.calls[0][0].removePassword).toBe(true);

    viewModel.reset(SHARE);
    viewModel.removePassword = true;
    viewModel.password = "neues Passwort";
    await viewModel.submit();
    const draft = save.mock.calls[1][0];
    expect(draft.password).toBe("neues Passwort");
    // Two answers to one question otherwise: the new password is the one that counts.
    expect(draft.removePassword).toBe(false);
});

test("emptying the form puts it back on a new link, not on the one that was edited", () => {
    const {viewModel} = editing();

    viewModel.reset();

    expect(viewModel.isEditing).toBe(false);
    expect(viewModel.replacingPassword).toBe(false);
    expect(viewModel.shareName).toBe("");
    expect(viewModel.expiry).toBe("7d");
    expect(viewModel.expiresOn).toBe("");
});

test("a write that failed leaves the form standing with what was typed in it", async () => {
    const {viewModel} = writing(async () => Promise.reject(new Error("nope")));
    viewModel.shareName = "Projektgruppe";

    expect(await viewModel.submit()).toBeNull();
    expect(viewModel.saveState).toEqual({type: "failed"});
    expect(viewModel.shareName).toBe("Projektgruppe");
});
