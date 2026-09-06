import {expect, mock, test} from "bun:test";
import {NewEmailAccountViewModel} from "./NewEmailAccountViewModel.svelte";
import type {
    FolderStreamEvent,
    ImapHostTest,
    ImapLoginTest,
    InboxConnection,
    InboxSetupRepository,
    SubmitInboxFolder,
} from "$lib/repository/InboxSetupRepository";

const REACHABLE: ImapHostTest = {reachable: true, outcome: "reachable", capabilities: ["IMAP4rev1"]};
const AUTHENTICATED: ImapLoginTest = {authenticated: true, outcome: "authenticated"};

/** A repository whose answers the test hands out one by one, so the order can be controlled. */
function testing(answer: (host: string, port: number, signal?: AbortSignal) => Promise<ImapHostTest>) {
    const probe = mock(answer);
    return {repository: {testImapHost: probe} as unknown as InboxSetupRepository, probe};
}

/** A repository that gets through every step, with the folder stream under the test's control. */
function walkingThrough(options: {
    login?: () => Promise<ImapLoginTest>;
    folders?: () => AsyncGenerator<FolderStreamEvent>;
} = {}) {
    const login = mock(options.login ?? (async () => AUTHENTICATED));
    const streamFolders = mock(options.folders ?? (async function* () {}));
    return {
        repository: {
            testImapHost: mock(async () => REACHABLE),
            testImapLogin: login,
            streamFolders,
        } as unknown as InboxSetupRepository,
        login,
        streamFolders,
    };
}

/** Fills in a host and a login and lands on the folder step. */
async function atFolderStep(repository: InboxSetupRepository) {
    const viewModel = new NewEmailAccountViewModel(repository);
    viewModel.setHost("imap.example.com");
    await tick(600);
    viewModel.goToNextStep();
    viewModel.setUsername("julius");
    viewModel.setPassword("secret");
    await tick(1000);
    viewModel.goToNextStep();
    await tick(50);
    return viewModel;
}

const TREE: FolderStreamEvent = {
    type: "folders",
    folders: [
        {path: ["Archiv"], fullName: "Archiv", name: "Archiv", delimiter: ".", specialType: null},
        {
            path: ["Archiv", "Newsletter"],
            fullName: "Archiv.Newsletter",
            name: "Newsletter",
            delimiter: ".",
            specialType: null,
        },
        {path: ["INBOX"], fullName: "INBOX", name: "INBOX", delimiter: ".", specialType: "INBOX"},
        {path: ["Trash"], fullName: "Trash", name: "Trash", delimiter: ".", specialType: "TRASH"},
    ],
};

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

test("a step only opens once the one before it checked out", async () => {
    const {repository} = walkingThrough();
    const viewModel = new NewEmailAccountViewModel(repository);

    expect(viewModel.canEnter("server")).toBe(true);
    expect(viewModel.canEnter("credentials")).toBe(false);
    expect(viewModel.canEnter("folders")).toBe(false);

    viewModel.setHost("imap.example.com");
    await tick(600);
    expect(viewModel.canEnter("credentials")).toBe(true);
    // The host answering says nothing about the credentials, which have not been typed.
    expect(viewModel.canEnter("folders")).toBe(false);

    viewModel.goToNextStep();
    expect(viewModel.step).toBe("credentials");

    viewModel.setUsername("julius");
    viewModel.setPassword("secret");
    await tick(1000);
    expect(viewModel.imapLoginTest).toEqual({type: "authenticated"});
    expect(viewModel.canEnter("folders")).toBe(true);
});

test("credentials are not tried until both fields have something in them", async () => {
    const {repository, login} = walkingThrough();
    const viewModel = new NewEmailAccountViewModel(repository);

    viewModel.setUsername("julius");
    await tick(1000);
    expect(login).toHaveBeenCalledTimes(0);
    expect(viewModel.imapLoginTest).toEqual({type: "idle"});

    viewModel.setPassword("secret");
    await tick(1000);
    expect(login).toHaveBeenCalledTimes(1);
});

test("a rejected password keeps its reason and closes the folder step again", async () => {
    const {repository} = walkingThrough({
        login: async () => ({authenticated: false, outcome: "invalid_credentials"}),
    });
    const viewModel = new NewEmailAccountViewModel(repository);

    viewModel.setHost("imap.example.com");
    await tick(600);
    viewModel.setUsername("julius");
    viewModel.setPassword("wrong");
    await tick(1000);

    expect(viewModel.imapLoginTest).toEqual({type: "rejected", outcome: "invalid_credentials"});
    expect(viewModel.canEnter("folders")).toBe(false);
});

test("editing the host throws away the login that was verified against the old one", async () => {
    const {repository} = walkingThrough();
    const viewModel = new NewEmailAccountViewModel(repository);

    viewModel.setHost("imap.example.com");
    await tick(600);
    viewModel.setUsername("julius");
    viewModel.setPassword("secret");
    await tick(1000);
    expect(viewModel.canEnter("folders")).toBe(true);

    viewModel.setHost("imap.other.example");
    expect(viewModel.imapLoginTest).toEqual({type: "idle"});
    expect(viewModel.canEnter("folders")).toBe(false);
});

test("the tree is drawn before the counting, and the numbers fill it in", async () => {
    const {repository} = walkingThrough({
        folders: async function* () {
            yield TREE;
            yield {type: "stats", stats: {fullName: "INBOX", mailCount: 535, oldestMailAt: "2025-12-27T23:34:15Z"}};
            yield {type: "done"};
        },
    });

    const viewModel = await atFolderStep(repository);

    expect(viewModel.folders.map((f) => f.fullName)).toEqual(["Archiv", "Archiv.Newsletter", "INBOX", "Trash"]);
    expect(viewModel.folderScan).toEqual({type: "done"});

    const inbox = viewModel.folders.find((f) => f.fullName === "INBOX")!;
    expect(inbox.mailCount).toBe(535);
    expect(inbox.oldestMailAt).toBe("2025-12-27T23:34:15Z");
    expect(inbox.counted).toBe(true);

    // Never counted, so it shows as pending rather than as an empty folder.
    const trash = viewModel.folders.find((f) => f.fullName === "Trash")!;
    expect(trash.counted).toBe(false);
    expect(trash.mailCount).toBeNull();
});

test("nesting comes off the path, and only a folder with children can be collapsed", async () => {
    const {repository} = walkingThrough({
        folders: async function* () {
            yield TREE;
            yield {type: "done"};
        },
    });

    const viewModel = await atFolderStep(repository);

    expect(viewModel.folders.map((f) => [f.name, f.depth, f.hasChildren])).toEqual([
        ["Archiv", 0, true],
        ["Newsletter", 1, false],
        ["INBOX", 0, false],
        ["Trash", 0, false],
    ]);

    expect(viewModel.visibleFolders).toHaveLength(4);
    viewModel.toggleCollapsed("Archiv");
    // The parent stays, its child goes.
    expect(viewModel.visibleFolders.map((f) => f.fullName)).toEqual(["Archiv", "INBOX", "Trash"]);
    viewModel.toggleCollapsed("Archiv");
    expect(viewModel.visibleFolders).toHaveLength(4);
});

test("the inbox is on by default and nothing else is", async () => {
    const {repository} = walkingThrough({
        folders: async function* () {
            yield TREE;
            yield {type: "done"};
        },
    });

    const viewModel = await atFolderStep(repository);

    // Trash off by default: importing it would pull in exactly what was thrown away.
    expect(viewModel.folders.filter((f) => f.enabled).map((f) => f.fullName)).toEqual(["INBOX"]);
    // Same for watching live -- a held-open connection per folder is not a sensible default.
    expect(viewModel.folders.filter((f) => f.realtime).map((f) => f.fullName)).toEqual(["INBOX"]);
    expect(viewModel.folders.every((f) => f.aiProcessing.type === "new_only")).toBe(true);

    viewModel.toggleFolder("Archiv.Newsletter");
    viewModel.toggleRealtime("Archiv.Newsletter");
    viewModel.setAiProcessing("Archiv.Newsletter", {type: "newest", count: 50});

    const newsletter = viewModel.folders.find((f) => f.fullName === "Archiv.Newsletter")!;
    expect(newsletter.enabled).toBe(true);
    expect(newsletter.realtime).toBe(true);
    expect(newsletter.aiProcessing).toEqual({type: "newest", count: 50});

    // Turning the folder off leaves its settings alone, so switching it back on restores them.
    viewModel.toggleFolder("Archiv.Newsletter");
    expect(newsletter.enabled).toBe(false);
    expect(newsletter.realtime).toBe(true);
});

test("a mailbox that could not be opened is a failed scan, not an empty one", async () => {
    const {repository} = walkingThrough({
        folders: async function* () {
            yield {type: "error", outcome: "mailbox_unavailable"};
        },
    });

    const viewModel = await atFolderStep(repository);

    expect(viewModel.folderScan).toEqual({type: "failed", outcome: "mailbox_unavailable"});
    expect(viewModel.folders).toEqual([]);
});

test("the scan runs once on arriving at the step, not on every visit", async () => {
    const {repository, streamFolders} = walkingThrough({
        folders: async function* () {
            yield TREE;
            yield {type: "done"};
        },
    });

    const viewModel = await atFolderStep(repository);
    expect(streamFolders).toHaveBeenCalledTimes(1);

    viewModel.goTo("credentials");
    viewModel.goTo("folders");
    await tick(50);
    expect(streamFolders).toHaveBeenCalledTimes(1);
});

test("enter goes on when the step has checked out", async () => {
    const {repository} = walkingThrough();
    const viewModel = new NewEmailAccountViewModel(repository);

    viewModel.setHost("imap.example.com");
    await tick(600);

    viewModel.submit();
    expect(viewModel.step).toBe("credentials");
});

test("enter runs a check that is still sitting out its debounce", async () => {
    const {repository} = walkingThrough();
    const hostProbe = (repository as any).testImapHost;
    const viewModel = new NewEmailAccountViewModel(repository);

    viewModel.setHost("imap.example.com");
    // Well inside the 500ms debounce: nothing has been asked yet.
    await tick(50);
    expect(hostProbe).toHaveBeenCalledTimes(0);

    viewModel.submit();
    await tick(50);

    // Enter is the user saying they are done typing, so the answer comes now, not at 500ms.
    expect(hostProbe).toHaveBeenCalledTimes(1);
    expect(viewModel.imapServerTest).toEqual({type: "reachable", capabilities: ["IMAP4rev1"]});
    // And it did not navigate on its own once the answer landed.
    expect(viewModel.step).toBe("server");
});

test("enter on a step that did not check out does not go on", async () => {
    const {repository} = walkingThrough({
        login: async () => ({authenticated: false, outcome: "invalid_credentials"}),
    });
    const viewModel = new NewEmailAccountViewModel(repository);

    viewModel.setHost("imap.example.com");
    await tick(600);
    viewModel.goToNextStep();
    viewModel.setUsername("julius");
    viewModel.setPassword("wrong");
    await tick(1100);

    viewModel.submit();
    expect(viewModel.step).toBe("credentials");
});

test("enter on the last step does nothing", async () => {
    const {repository} = walkingThrough({
        folders: async function* () {
            yield TREE;
            yield {type: "done"};
        },
    });

    const viewModel = await atFolderStep(repository);
    viewModel.submit();

    expect(viewModel.step).toBe("folders");
});

test("the debounce still fires on its own when enter is not pressed", async () => {
    const {repository} = walkingThrough();
    const hostProbe = (repository as any).testImapHost;
    const viewModel = new NewEmailAccountViewModel(repository);

    viewModel.setHost("imap.example.com");
    await tick(600);

    expect(hostProbe).toHaveBeenCalledTimes(1);
});

test("collapsing a folder hides its grandchildren too", async () => {
    // Three levels, as a real mailbox has them: Spam > Test2 > Test.
    const {repository} = walkingThrough({
        folders: async function* () {
            yield {
                type: "folders",
                folders: [
                    {path: ["INBOX"], fullName: "INBOX", name: "INBOX", delimiter: "/", specialType: "INBOX"},
                    {path: ["Spam"], fullName: "Spam", name: "Spam", delimiter: "/", specialType: "SPAM"},
                    {path: ["Spam", "Test2"], fullName: "Spam/Test2", name: "Test2", delimiter: "/", specialType: null},
                    {
                        path: ["Spam", "Test2", "Test"],
                        fullName: "Spam/Test2/Test",
                        name: "Test",
                        delimiter: "/",
                        specialType: null,
                    },
                ],
            };
            yield {type: "done"};
        },
    });

    const viewModel = await atFolderStep(repository);

    expect(viewModel.folders.map((f) => [f.name, f.depth, f.hasChildren])).toEqual([
        ["INBOX", 0, false],
        ["Spam", 0, true],
        ["Test2", 1, true],
        ["Test", 2, false],
    ]);

    // Collapsing the top of the branch takes the whole branch, not just one level of it.
    viewModel.toggleCollapsed("Spam");
    expect(viewModel.visibleFolders.map((f) => f.fullName)).toEqual(["INBOX", "Spam"]);

    // And collapsing the middle leaves the middle visible under its parent.
    viewModel.toggleCollapsed("Spam");
    viewModel.toggleCollapsed("Spam/Test2");
    expect(viewModel.visibleFolders.map((f) => f.fullName)).toEqual(["INBOX", "Spam", "Spam/Test2"]);
});

/** Gets to the folder step with the given rows and returns what a submit would send. */
async function submittedPayload(prepare: (viewModel: NewEmailAccountViewModel) => void) {
    // Typed, so `mock.calls[0]` is the argument tuple rather than an empty one.
    const submit = mock(
        async (_imap: InboxConnection, _folders: SubmitInboxFolder[]) => ({type: "created", id: "acc-1"}) as const,
    );
    const repository = {
        testImapHost: mock(async () => REACHABLE),
        testImapLogin: mock(async () => AUTHENTICATED),
        streamFolders: mock(async function* () {
            yield TREE;
            yield {type: "done"} as const;
        }),
        submitInbox: submit,
    } as unknown as InboxSetupRepository;

    const viewModel = await atFolderStep(repository);
    prepare(viewModel);
    const ok = await viewModel.submitInbox();
    return {ok, submit, viewModel};
}

test("only the folders that are kept are sent, with their settings", async () => {
    const {ok, submit} = await submittedPayload((viewModel) => {
        viewModel.toggleFolder("Archiv.Newsletter");
        viewModel.setAiProcessing("Archiv.Newsletter", {type: "newest", count: 250});
        viewModel.toggleRealtime("Archiv.Newsletter");
    });

    expect(ok).toBe(true);
    expect(submit).toHaveBeenCalledTimes(1);

    const [imap, folders] = submit.mock.calls[0];
    expect(imap).toEqual({host: "imap.example.com", port: 993, username: "julius", password: "secret"});
    // INBOX is on by default, Newsletter was just switched on; Archiv and Trash stay out.
    expect(folders).toEqual([
        {folderName: "Archiv.Newsletter", imapPush: true, aiImport: {type: "newest_messages", count: 250}},
        {folderName: "INBOX", imapPush: true, aiImport: {type: "only_new_messages"}},
    ]);
});

test("a date is sent as local midnight of the day that was picked", async () => {
    const {submit} = await submittedPayload((viewModel) => {
        viewModel.setAiProcessing("INBOX", {type: "since", date: "2026-09-05"});
    });

    const folders = submit.mock.calls[0][1];
    const scope = folders.find((f) => f.folderName === "INBOX")!.aiImport as {type: string; timestamp: number};
    expect(scope.type).toBe("after_date");
    // The user picked a day in their own calendar, so that is the day the boundary belongs to.
    expect(new Date(scope.timestamp * 1000)).toEqual(new Date(2026, 8, 5));
});

test("every assistant mode has a wire form", async () => {
    for (const [mode, expected] of [
        [{type: "all"}, {type: "all_messages"}],
        [{type: "new_only"}, {type: "only_new_messages"}],
        [{type: "newest", count: 7}, {type: "newest_messages", count: 7}],
    ] as const) {
        const {submit} = await submittedPayload((viewModel) => {
            viewModel.setAiProcessing("INBOX", mode);
        });
        const folders = submit.mock.calls[0][1];
        expect(folders.find((f) => f.folderName === "INBOX")!.aiImport).toEqual(expected);
    }
});

test("an inbox with no folder kept cannot be submitted", async () => {
    const {ok, submit} = await submittedPayload((viewModel) => {
        // INBOX is the only one on by default; switching it off leaves nothing to create.
        viewModel.toggleFolder("INBOX");
    });

    expect(ok).toBe(false);
    expect(submit).toHaveBeenCalledTimes(0);
});

test("a mailbox that is already set up is reported, not thrown", async () => {
    const repository = {
        testImapHost: mock(async () => REACHABLE),
        testImapLogin: mock(async () => AUTHENTICATED),
        streamFolders: mock(async function* () {
            yield TREE;
            yield {type: "done"} as const;
        }),
        submitInbox: mock(async () => ({type: "conflict"}) as const),
    } as unknown as InboxSetupRepository;

    const viewModel = await atFolderStep(repository);
    expect(await viewModel.submitInbox()).toBe(false);
    expect(viewModel.submitState).toEqual({type: "conflict"});
    // The form stays as it was, so the user can go back and change the account.
    expect(viewModel.step).toBe("folders");
});

test("a failed submit keeps the form; a reset empties it", async () => {
    const repository = {
        testImapHost: mock(async () => REACHABLE),
        testImapLogin: mock(async () => AUTHENTICATED),
        streamFolders: mock(async function* () {
            yield TREE;
            yield {type: "done"} as const;
        }),
        submitInbox: mock(async () => {
            throw new Error("offline");
        }),
    } as unknown as InboxSetupRepository;

    const viewModel = await atFolderStep(repository);
    expect(await viewModel.submitInbox()).toBe(false);
    expect(viewModel.submitState).toEqual({type: "failed"});
    expect(viewModel.folders.length).toBeGreaterThan(0);

    viewModel.reset();
    expect(viewModel.step).toBe("server");
    expect(viewModel.host).toBe("");
    expect(viewModel.password).toBe("");
    expect(viewModel.folders).toEqual([]);
    expect(viewModel.submitState).toEqual({type: "idle"});
});

/** The same form, opened on a mailbox that exists. */
function editing(options: {
    folders?: () => AsyncGenerator<FolderStreamEvent>;
    save?: () => Promise<{type: "created"; id: string} | {type: "conflict"}>;
} = {}) {
    // Typed, so `mock.calls[0]` is the argument tuple rather than an empty one -- the inbox id
    // in the last position is the whole point of these tests.
    const login = mock(
        async (
            _host: string,
            _port: number,
            _username: string,
            _password: string,
            _signal?: AbortSignal,
            _inboxId?: string,
        ) => AUTHENTICATED,
    );
    const streamFolders = mock(async function* (
        _host: string,
        _port: number,
        _username: string,
        _password: string,
        _signal?: AbortSignal,
        _inboxId?: string,
    ): AsyncGenerator<FolderStreamEvent> {
        yield* options.folders?.() ?? (async function* () {
            yield TREE;
            yield {type: "done"} as const;
        })();
    });
    const save = mock(
        async (_imap: InboxConnection, _folders: SubmitInboxFolder[]) =>
            (options.save ? await options.save() : ({type: "created", id: "acc-1"} as const)),
    );
    const repository = {
        testImapHost: mock(async () => REACHABLE),
        testImapLogin: login,
        streamFolders,
    } as unknown as InboxSetupRepository;

    const viewModel = new NewEmailAccountViewModel(repository, {inboxId: "acc-1", save});
    return {viewModel, login, streamFolders, save};
}

const STORED = {
    id: "acc-1",
    host: "imap.example.com",
    port: 993,
    username: "julius",
    isPaused: false,
    folders: [
        {folderName: "Archiv.Newsletter", imapPush: true, aiImport: {type: "all_messages"}},
        {folderName: "Trash", imapPush: false, aiImport: {type: "after_date", timestamp: 1757023200}},
    ],
};

test("opening on a mailbox fills the connection in and leaves the password empty", async () => {
    const {viewModel} = editing();

    viewModel.prefill(STORED as never);

    expect(viewModel.host).toBe("imap.example.com");
    expect(viewModel.port).toBe(993);
    expect(viewModel.username).toBe("julius");
    // The server never hands one out, and empty means "unchanged" everywhere it is sent.
    expect(viewModel.password).toBe("");
    expect(viewModel.isEditing).toBe(true);
});

test("an empty password is still checked when editing, and is not when creating", async () => {
    const edit = editing();
    edit.viewModel.prefill(STORED as never);
    await tick(1100);
    expect(edit.login).toHaveBeenCalledTimes(1);
    // The inbox id is what lets the server fill the stored password in.
    expect(edit.login.mock.calls[0][5]).toBe("acc-1");
    expect(edit.viewModel.imapLoginTest).toEqual({type: "authenticated"});

    const {repository, login} = walkingThrough();
    const create = new NewEmailAccountViewModel(repository);
    create.setHost("imap.example.com");
    create.setUsername("julius");
    await tick(1100);
    expect(login).toHaveBeenCalledTimes(0);
});

test("the folder table opens on what the mailbox is configured with", async () => {
    const {viewModel, streamFolders} = editing();
    viewModel.prefill(STORED as never);
    await tick(1100);
    viewModel.goTo("folders");
    await tick(60);

    // The scan goes to the route for this mailbox, not the setup one.
    expect(streamFolders.mock.calls[0][5]).toBe("acc-1");

    const byName = Object.fromEntries(viewModel.folders.map((f) => [f.fullName, f]));
    // Configured folders open on their settings...
    expect(byName["Archiv.Newsletter"].enabled).toBe(true);
    expect(byName["Archiv.Newsletter"].realtime).toBe(true);
    expect(byName["Archiv.Newsletter"].aiProcessing).toEqual({type: "all"});
    expect(byName["Trash"].enabled).toBe(true);
    expect(byName["Trash"].aiProcessing.type).toBe("since");
    // ...and one that has appeared since is off, like any folder nobody picked.
    expect(byName["INBOX"].enabled).toBe(false);
    expect(byName["Archiv"].enabled).toBe(false);
});

test("saving an edit writes to the mailbox instead of creating a second one", async () => {
    const {viewModel, save} = editing();
    viewModel.prefill(STORED as never);
    await tick(1100);
    viewModel.goTo("folders");
    await tick(60);

    expect(await viewModel.submitInbox()).toBe(true);
    expect(save).toHaveBeenCalledTimes(1);

    const [imap, folders] = save.mock.calls[0];
    expect(imap).toEqual({host: "imap.example.com", port: 993, username: "julius", password: ""});
    // Only what is kept, and the settings it was opened on.
    expect(folders.map((f) => f.folderName).sort()).toEqual(["Archiv.Newsletter", "Trash"]);
});

test("a mailbox the edit would collide with is reported, not thrown", async () => {
    const {viewModel} = editing({save: async () => ({type: "conflict"}) as const});
    viewModel.prefill(STORED as never);
    await tick(1100);
    viewModel.goTo("folders");
    await tick(60);

    expect(await viewModel.submitInbox()).toBe(false);
    expect(viewModel.submitState).toEqual({type: "conflict"});
});
