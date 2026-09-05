<script lang="ts">
    /**
     * The "new inbox" dialog on stubbed answers, so the folder table can be looked at without a
     * mailbox to log into. The tree below is the shape of a real one: a nested Archiv, the special
     * folders beside it, one folder that could not be read.
     */
    import NewEmailAccountDialog from "$lib/app/settings/email-accounts/new/NewEmailAccountDialog.svelte";
    import {createRepositories, provideRepositories} from "$lib/repository/repositories";
    import type {FolderStreamEvent, InboxSetupRepository} from "$lib/repository/InboxSetupRepository";

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

    provideRepositories(createRepositories({inboxSetup}));

    let open = $state(true);
</script>

<div class="p-8">
    <button class="underline" onclick={() => (open = true)}>open</button>
</div>

<NewEmailAccountDialog bind:open />
