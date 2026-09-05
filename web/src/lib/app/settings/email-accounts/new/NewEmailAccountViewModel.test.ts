import {expect, mock, test} from "bun:test";
import {NewEmailAccountViewModel} from "./NewEmailAccountViewModel.svelte";
import type {ImapHostTest, InboxSetupRepository} from "$lib/repository/InboxSetupRepository";

const REACHABLE: ImapHostTest = {reachable: true, outcome: "reachable", capabilities: ["IMAP4rev1"]};

/** A repository whose answers the test hands out one by one, so the order can be controlled. */
function testing(answer: (host: string, port: number, signal?: AbortSignal) => Promise<ImapHostTest>) {
    const probe = mock(answer);
    return {repository: {testImapHost: probe} as unknown as InboxSetupRepository, probe};
}

const tick = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

test("the host is only tested once the typing stops", async () => {
    const {repository, probe} = testing(async () => REACHABLE);
    const viewModel = new NewEmailAccountViewModel(repository);

    "imap.example.com".split("").forEach((_, index, all) => {
        viewModel.setHost(all.slice(0, index + 1).join(""));
    });
    expect(probe).toHaveBeenCalledTimes(0);

    await tick(600);
    expect(probe).toHaveBeenCalledTimes(1);
    expect(probe.mock.calls[0].slice(0, 2)).toEqual(["imap.example.com", 993]);
    expect(viewModel.imapServerTest).toEqual({type: "reachable", capabilities: ["IMAP4rev1"]});
});

test("a half-filled form is not tested at all", async () => {
    const {repository, probe} = testing(async () => REACHABLE);
    const viewModel = new NewEmailAccountViewModel(repository);

    viewModel.setHost("   ");
    await tick(600);
    expect(probe).toHaveBeenCalledTimes(0);

    // A host, but no port worth connecting to.
    viewModel.setHost("imap.example.com");
    viewModel.setPort(Number.NaN);
    await tick(600);
    expect(probe).toHaveBeenCalledTimes(0);
    expect(viewModel.imapServerTest).toEqual({type: "idle"});
});

test("an answer about a host that has since been edited away is dropped", async () => {
    // The first host answers slowly, the second one at once -- the order the race is about.
    const {repository, probe} = testing(async (host) => {
        await tick(host === "slow.example.com" ? 200 : 0);
        return {reachable: host !== "slow.example.com", outcome: "reachable", capabilities: []};
    });
    const viewModel = new NewEmailAccountViewModel(repository);

    viewModel.setHost("slow.example.com");
    await tick(600);
    viewModel.setHost("imap.example.com");
    await tick(600);

    expect(probe).toHaveBeenCalledTimes(2);
    // The slow "unreachable" resolves last, and must not overwrite this.
    expect(viewModel.imapServerTest).toEqual({type: "reachable", capabilities: []});
});

test("the reason a host is unreachable is kept, so the dialog can name it", async () => {
    const {repository} = testing(async () => ({reachable: false, outcome: "tls_failed", capabilities: []}));
    const viewModel = new NewEmailAccountViewModel(repository);

    viewModel.setHost("imap.example.com");
    viewModel.setPort(143);
    await tick(600);

    expect(viewModel.imapServerTest).toEqual({type: "unreachable", outcome: "tls_failed"});
});

test("a request that does not happen is not a verdict about the host", async () => {
    const {repository} = testing(async () => {
        throw new Error("offline");
    });
    const viewModel = new NewEmailAccountViewModel(repository);

    viewModel.setHost("imap.example.com");
    await tick(600);

    expect(viewModel.imapServerTest).toEqual({type: "failed"});
});

test("closing the dialog mid-test leaves no spinner behind for the next one", async () => {
    const {repository} = testing(async () => {
        await tick(200);
        return REACHABLE;
    });
    const viewModel = new NewEmailAccountViewModel(repository);

    viewModel.setHost("imap.example.com");
    await tick(600);
    expect(viewModel.imapServerTest).toEqual({type: "testing"});

    viewModel.dispose();
    expect(viewModel.imapServerTest).toEqual({type: "idle"});

    // And the answer that was already on its way does not arrive late either.
    await tick(400);
    expect(viewModel.imapServerTest).toEqual({type: "idle"});
});

test("closing the dialog cancels a test that was scheduled", async () => {
    const {repository, probe} = testing(async () => REACHABLE);
    const viewModel = new NewEmailAccountViewModel(repository);

    viewModel.setHost("imap.example.com");
    viewModel.dispose();
    await tick(600);

    expect(probe).toHaveBeenCalledTimes(0);
});
