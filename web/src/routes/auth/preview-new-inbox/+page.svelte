<script lang="ts">
    /**
     * The "new inbox" dialog on stubbed answers, so the folder table can be looked at without a
     * mailbox to log into. The tree below is the shape of a real one: a nested Archiv, the special
     * folders beside it, one folder that could not be read.
     */
    import EmailAccountsSettings from "$lib/app/settings/email-accounts/EmailAccountsSettings.svelte";
    import {createRepositories, provideRepositories} from "$lib/repository/repositories";
    import type {FolderStreamEvent, InboxSetupRepository} from "$lib/repository/InboxSetupRepository";
    import type {Inbox, InboxRepository} from "$lib/repository/InboxRepository";

    const FOLDERS = [
        ["Archiv", null],
        ["Archiv.Bestellungen, Zahlungen", null],
        ["Archiv.Nachrichten", null],
        ["Archiv.Newsletter", null],
        ["Drafts", "DRAFTS"],
        ["INBOX", "INBOX"],
        ["Sent Items", "SENT"],
        ["Spam", "SPAM"],
        ["Trash", "TRASH"],
    ] as const;

    const COUNTS: Record<string, [number | null, string | null]> = {
        "Archiv": [0, null],
        "Archiv.Bestellungen, Zahlungen": [747, "2023-05-06T10:47:29Z"],
        "Archiv.Nachrichten": [2649, "2022-08-28T09:13:47Z"],
        "Archiv.Newsletter": [1181, "2025-06-30T15:39:48Z"],
        "Drafts": [2, "2026-07-16T14:27:54Z"],
        "INBOX": [535, "2025-12-27T23:34:15Z"],
        "Sent Items": [834, "2014-03-21T16:31:04Z"],
        // What an unreadable folder looks like.
        "Spam": [null, null],
        "Trash": [58, "2026-07-26T12:53:41Z"],
    };

    const wait = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

    const inboxSetup = {
        testImapHost: async () => ({reachable: true, outcome: "reachable", capabilities: ["IMAP4rev1"]}),
        testImapLogin: async () => ({authenticated: true, outcome: "authenticated"}),
        submitInbox: async () => {
            await wait(300);
            connected = [
                ...connected,
                {id: "acc-2", host: "imap.mail.de", port: 993, username: "new@example.com", folders: ["INBOX"], emailCount: 0, isPaused: false},
            ];
            return {type: "created", id: "acc-2"} as const;
        },
        async *streamFolders(): AsyncGenerator<FolderStreamEvent> {
            await wait(400);
            yield {
                type: "folders",
                folders: FOLDERS.map(([fullName, specialType]) => ({
                    path: fullName.split("."),
                    fullName,
                    name: fullName.split(".").at(-1)!,
                    delimiter: ".",
                    specialType,
                })),
            };
            for (const [fullName] of FOLDERS) {
                await wait(250);
                const [mailCount, oldestMailAt] = COUNTS[fullName];
                yield {type: "stats", stats: {fullName, mailCount, oldestMailAt}};
            }
            yield {type: "done"};
        },
    } as unknown as InboxSetupRepository;

    /** Grows when the dialog reports a creation, which is what the reload callback is for. */
    let connected: Inbox[] = [
        {id: "acc-1", host: "imap.strato.de", port: 993, username: "julius@example.com", folders: [
            "INBOX", "Sent", "Drafts", "Trash", "Spam", "Archiv", "Archiv/Newsletter",
            "Archiv/Bestellungen, Zahlungen", "Archiv/Nachrichten", "Archiv/Rechtliches", "Archiv/Systeme",
        ], emailCount: 2649, isPaused: false},
    ];

    const inboxes = {
        list: async () => {
            await wait(200);
            return [...connected];
        },
        remove: async (id: string) => {
            await wait(400);
            const gone = connected.find((inbox) => inbox.id === id);
            connected = connected.filter((inbox) => inbox.id !== id);
            return gone?.emailCount ?? 0;
        },
    } as unknown as InboxRepository;

    provideRepositories(createRepositories({inboxSetup, inboxes}));
</script>

<!--
  Width-limited on purpose: in the app this screen sits inside the settings dialog, and a page
  that is free to grow sideways would scroll the whole document instead of the table, which is
  the one thing worth looking at here.
-->
<div class="p-8">
    <div class="w-[42rem] overflow-hidden">
        <EmailAccountsSettings />
    </div>
</div>
