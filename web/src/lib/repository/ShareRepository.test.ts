import {expect, mock, test} from "bun:test";
import {ShareRepository} from "./ShareRepository";

function answering(status: number, body: unknown = null) {
    const fetcher = mock(async () => new Response(body === null ? "" : JSON.stringify(body), {status}));
    globalThis.fetch = fetcher as unknown as typeof fetch;
    return fetcher;
}

const MAIL = "11111111-2222-3333-4444-555555555555";

const SHARE = {
    id: "s-1",
    share_name: "Projektgruppe",
    shared_at: 1772000000,
    valid_until: 1772600000,
    include_labels: true,
    has_password: true,
    allow_metadata_without_password: true,
};

const DRAFT = {
    shareName: "Projektgruppe",
    includeLabels: true,
    validUntil: 1772600000,
    password: "hunter2",
    allowMetadataWithoutPassword: true,
};

test("reads the links that were made for a mail", async () => {
    const fetcher = answering(200, {shares: [SHARE]});

    expect(await new ShareRepository().list(MAIL)).toEqual([
        {
            id: "s-1",
            shareName: "Projektgruppe",
            sharedAt: 1772000000,
            validUntil: 1772600000,
            includeLabels: true,
            hasPassword: true,
            allowMetadataWithoutPassword: true,
        },
    ]);
    expect((fetcher as any).mock.calls[0][0]).toBe(`/api/emails/${MAIL}/shares`);
});

test("a share without a name, a date or a password is still a share", async () => {
    answering(200, {shares: [{id: "s-2", shared_at: 1772000000}]});

    const share = (await new ShareRepository().list(MAIL))[0];
    expect(share.shareName).toBeNull();
    expect(share.validUntil).toBeNull();
    // The dialog decides on these, so an absent flag must not read as true.
    expect(share.hasPassword).toBe(false);
    expect(share.includeLabels).toBe(false);
    expect(share.allowMetadataWithoutPassword).toBe(false);
});

test("a list that could not be read throws, so the dialog does not claim there are no links", async () => {
    answering(500);
    expect(new ShareRepository().list(MAIL)).rejects.toThrow();
});

test("creating sends the whole share and reads back what was stored", async () => {
    const fetcher = answering(201, SHARE);

    const created = await new ShareRepository().create(MAIL, DRAFT);

    const request = (fetcher as any).mock.calls[0][1];
    expect(request.method).toBe("POST");
    expect(JSON.parse(request.body)).toEqual({
        share_name: "Projektgruppe",
        include_labels: true,
        valid_until: 1772600000,
        password: "hunter2",
        remove_password: false,
        allow_metadata_without_password: true,
    });
    expect(created.id).toBe("s-1");
});

test("an edit without a password leaves the one that is there", async () => {
    const fetcher = answering(200, SHARE);

    await new ShareRepository().update(MAIL, "s-1", {...DRAFT, password: ""});

    const request = (fetcher as any).mock.calls[0][1];
    expect(request.method).toBe("PUT");
    // Blank is an untouched field, not "set this to nothing" -- that is `removePassword`.
    expect(JSON.parse(request.body).password).toBeNull();
    expect(JSON.parse(request.body).remove_password).toBe(false);
    expect((fetcher as any).mock.calls[0][0]).toBe(`/api/emails/${MAIL}/shares/s-1`);
});

test("taking the password off says so, rather than sending an empty one", async () => {
    const fetcher = answering(200, {...SHARE, has_password: false});

    const saved = await new ShareRepository().update(MAIL, "s-1", {
        ...DRAFT,
        password: null,
        removePassword: true,
    });

    expect(JSON.parse((fetcher as any).mock.calls[0][1].body).remove_password).toBe(true);
    expect(saved.hasPassword).toBe(false);
});

test("deleting a link asks for the share under its mail", async () => {
    const fetcher = answering(204);

    await new ShareRepository().remove(MAIL, "s-1");

    expect((fetcher as any).mock.calls[0][0]).toBe(`/api/emails/${MAIL}/shares/s-1`);
    expect((fetcher as any).mock.calls[0][1].method).toBe("DELETE");
});

test("a delete that failed throws, so the link is not dropped from the list", async () => {
    answering(404);
    expect(new ShareRepository().remove(MAIL, "s-1")).rejects.toThrow();
});
