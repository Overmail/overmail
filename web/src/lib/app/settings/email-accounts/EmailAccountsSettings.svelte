<script lang="ts">
    import {Button} from "$lib/components/ui/button";
    import {Badge} from "$lib/components/ui/badge";
    import * as Empty from "$lib/components/ui/empty";
    import * as Table from "$lib/components/ui/table";
    import {Spinner} from "$lib/components/ui/spinner/index.ts";
    import {
        EnvelopeSimpleIcon,
        PauseIcon,
        PencilSimpleIcon,
        PlayIcon,
        PlusIcon,
        TrashIcon,
        WarningCircleIcon,
    } from "phosphor-svelte";
    import NewEmailAccountDialog from "$lib/app/settings/email-accounts/new/NewEmailAccountDialog.svelte";
    import DeleteInboxDialog from "$lib/app/settings/email-accounts/DeleteInboxDialog.svelte";
    import {useRepositories} from "$lib/repository/repositories";
    import type {Inbox} from "$lib/repository/InboxRepository";
    import {cn} from "$lib/utils";
    import {_} from "svelte-i18n";

    const {inboxes: inboxRepository} = useRepositories();

    let showNewEmailAccountDialog = $state(false);
    /** The mailbox the delete dialog is asking about; null while it is closed. */
    let inboxToDelete: Inbox | null = $state(null);
    /** Mailboxes whose pause is in flight, so the button cannot be pressed twice. */
    let pausing: string[] = $state([]);

    /**
     * Switches the import for one mailbox off or on.
     *
     * The row is patched rather than the list re-read: re-reading would put the whole table back
     * through its loading state for one flag. A failure does re-read, because then the screen and
     * the server disagree and the server is right.
     */
    async function togglePaused(inbox: Inbox) {
        if (pausing.includes(inbox.id)) return;
        pausing = [...pausing, inbox.id];

        try {
            await inboxRepository.setPaused(inbox.id, !inbox.isPaused);
            if (inboxes.type === "loaded") {
                inboxes = {
                    type: "loaded",
                    rows: inboxes.rows.map((row) =>
                        row.id === inbox.id ? {...row, isPaused: !inbox.isPaused} : row,
                    ),
                };
            }
        } catch {
            await load();
        } finally {
            pausing = pausing.filter((id) => id !== inbox.id);
        }
    }

    let inboxes: {type: "loading"} | {type: "loaded"; rows: Inbox[]} | {type: "failed"} = $state({
        type: "loading",
    });

    async function load() {
        inboxes = {type: "loading"};
        try {
            inboxes = {type: "loaded", rows: await inboxRepository.list()};
        } catch {
            // Not an empty state: "nothing could be read" and "you have no mailboxes" are
            // different answers, and only one of them means the user has something to do.
            inboxes = {type: "failed"};
        }
    }

    $effect(() => {
        void load();
    });

    /**
     * How the username column ends: a hard rule, and a shadow falling over what scrolls under it.
     *
     * Only worth drawing while something is actually behind the column -- `*_ON` is added once the
     * table is scrolled, and the `transition-shadow` on the cell fades it in.
     *
     * The actions column does not use this. It appears and disappears with the pointer rather than
     * standing there permanently, and a panel that comes and goes reads better running out into
     * the folders than snapping to a rule -- see its cell below.
     */
    const PINNED_LEFT_EDGE = "shadow-none";
    const PINNED_LEFT_EDGE_ON = "shadow-[1px_0_0_0_var(--border),8px_0_12px_-8px_rgb(0_0_0/0.28)]";

    /** The table itself; `Table.Root` puts it inside the wrapper that does the scrolling. */
    let tableElement: HTMLTableElement | null = $state(null);
    /** Whether anything is hidden past the left edge, which is what the username column marks. */
    let scrolledFromStart = $state(false);

    // A pinned column is only worth separating off while it is covering something. With the table
    // sitting still there is nothing behind it, and a rule down the middle would be a line for no
    // reason -- which is why this is measured rather than always drawn.
    $effect(() => {
        const table = tableElement;
        const scroller = table?.parentElement;
        if (!table || !scroller) return;

        const update = () => {
            scrolledFromStart = scroller.scrollLeft > 0;
        };
        update();

        scroller.addEventListener("scroll", update, {passive: true});
        // It also changes without anyone scrolling: the dialog resizes, and the table grows as
        // folders arrive, either of which can leave the scroll position back at the start.
        const observer = new ResizeObserver(update);
        observer.observe(scroller);
        observer.observe(table);

        return () => {
            scroller.removeEventListener("scroll", update);
            observer.disconnect();
        };
    });
</script>

<!-- `min-w-0` for the same reason as in SettingsDialog: the table must be allowed to scroll. -->
<div class="flex min-w-0 flex-1 flex-col grow">
    {#if inboxes.type === "loading"}
        <div class="text-muted-foreground flex flex-row items-start gap-2 text-sm">
            <Spinner class="mt-0.5 size-4 shrink-0" />
            <span>{$_("settings.emailAccounts.list.loading")}</span>
        </div>
    {:else if inboxes.type === "failed"}
        <div class="text-destructive flex flex-row items-start gap-2 text-sm">
            <WarningCircleIcon class="mt-0.5 size-4 shrink-0" />
            <span>{$_("settings.emailAccounts.list.failed")}</span>
        </div>
    {:else if inboxes.rows.length > 0}
        <div class="flex flex-col gap-4">
            <Button
                    class="w-fit"
                    onclick={() => (showNewEmailAccountDialog = true)}
            >
                <PlusIcon />
                {$_("settings.emailAccounts.add")}
            </Button>
            
            <!-- `Table.Root` brings its own horizontally scrolling wrapper; this one only rounds it off. -->
            <div class="overflow-hidden rounded-2xl border">
                <Table.Root class="text-sm" bind:ref={tableElement}>
                    <Table.Header>
                        <Table.Row>
                            <!--
                              Pinned while the rest scrolls sideways: the username is what says
                              which row is which, and a row identified by nothing is not a row.
                            -->
                            <Table.Head class={cn("bg-popover sticky left-0 z-20 transition-shadow", PINNED_LEFT_EDGE, scrolledFromStart && PINNED_LEFT_EDGE_ON)}>
                                {$_("settings.emailAccounts.list.columns.username")}
                            </Table.Head>
                            <Table.Head>
                                <span class="pl-1">{$_("settings.emailAccounts.list.columns.folders")}</span>
                            </Table.Head>
                            <!--
                              The actions column. Empty *and* transparent on purpose: the backdrop
                              under the buttons belongs to a row being hovered, and a header is
                              never hovered -- given one anyway it just sat there permanently.
                            -->
                            <Table.Head class="sticky right-0 z-20 w-32"></Table.Head>
                        </Table.Row>
                    </Table.Header>
                    <Table.Body>
                        {#each inboxes.rows as inbox (inbox.id)}
                            <Table.Row class="group/row hover:bg-muted">
                                <Table.Cell
                                        class={cn(
                                            "bg-popover group-hover/row:bg-muted sticky left-0 z-10 font-medium",
                                            "transition-[background-color,box-shadow]",
                                            PINNED_LEFT_EDGE,
                                            scrolledFromStart && PINNED_LEFT_EDGE_ON,
                                        )}
                                >
                                    <div class="flex flex-col">
                                        <span>
                                            {inbox.username}
                                        </span>
                                        <div class="flex flex-row gap-1 items-center text-muted-foreground whitespace-nowrap text-xs font-mono">
                                            {#if inbox.isPaused}
                                                <PauseIcon class="w-3 h-3" />
                                            {/if}
                                            <span>{inbox.host}:{inbox.port}</span>
                                        </div>
                                    </div>
                                </Table.Cell>
                                <Table.Cell>
                                    {#if inbox.folders.length === 0}
                                        <span class="text-muted-foreground pl-1">
                                            {$_("settings.emailAccounts.list.noFolders")}
                                        </span>
                                    {:else}
                                        <!--
                                          `flex-nowrap`: a folder list belongs on one line. It is
                                          what makes the table wider than the dialog, which is
                                          what the horizontal scroll is for.
                                        -->
                                        <!--
                                          `w-max` and `shrink-0`: without them the badges give way
                                          to the table's `w-full` and squash instead of pushing the
                                          row past the edge, so there would be nothing to scroll.
                                        -->
                                        <div class="flex w-max flex-row flex-nowrap gap-1">
                                            {#each inbox.folders as folder (folder)}
                                                <Badge variant="secondary" class="shrink-0 whitespace-nowrap">
                                                    {folder}
                                                </Badge>
                                            {/each}
                                        </div>
                                    {/if}
                                </Table.Cell>

                                <!--
                                  A column of its own rather than an overlay on the folders: the
                                  table scrolls sideways now, and an overlay would scroll away with
                                  the cell it sat in. Sticky, so the actions stay reachable wherever
                                  the folder list has been scrolled to.
                                -->
                                <Table.Cell class="sticky right-0 z-10 w-32 pl-10">
                                    <!--
                                      The backdrop comes in with the buttons: they are the only
                                      thing that needs covering, and until the row is hovered the
                                      folders may run the full width.

                                      Two boxes rather than one, and no rule: it runs out over the
                                      2rem the padding keeps free, so the folders disappear under
                                      the buttons instead of stopping at a line. `transition`, not
                                      `transition-colors`, so appearing and the hover colour share
                                      the row's 150ms.
                                    -->
                                    <div
                                            class="to-popover group-hover/row:to-muted pointer-events-none absolute
                                                   inset-y-0 left-0 w-8 bg-linear-to-r from-transparent opacity-0
                                                   transition group-hover/row:opacity-100"
                                    ></div>
                                    <div
                                            class="bg-popover group-hover/row:bg-muted pointer-events-none absolute
                                                   inset-y-0 right-0 left-8 opacity-0 transition
                                                   group-hover/row:opacity-100"
                                    ></div>
                                    <div
                                            class="relative flex flex-row items-center justify-end gap-1 opacity-0
                                                   transition-opacity group-hover/row:opacity-100 focus-within:opacity-100"
                                    >
                                        <!-- TODO: no editing screen yet, see the note in the PR. -->
                                        <Button
                                                variant="ghost"
                                                size="icon-sm"
                                                disabled
                                                aria-label={$_("settings.emailAccounts.list.actions.edit")}
                                                title={$_("settings.emailAccounts.list.actions.edit")}
                                        >
                                            <PencilSimpleIcon />
                                        </Button>
                                        <!--
                                          Between edit and delete, and the only one of the three
                                          that is reversible -- which is why the delete dialog
                                          offers it as the way out.
                                        -->
                                        <Button
                                                variant="ghost"
                                                size="icon-sm"
                                                disabled={pausing.includes(inbox.id)}
                                                aria-label={$_(
                                                    inbox.isPaused
                                                        ? "settings.emailAccounts.list.actions.resume"
                                                        : "settings.emailAccounts.list.actions.pause",
                                                )}
                                                title={$_(
                                                    inbox.isPaused
                                                        ? "settings.emailAccounts.list.actions.resume"
                                                        : "settings.emailAccounts.list.actions.pause",
                                                )}
                                                onclick={() => togglePaused(inbox)}
                                        >
                                            {#if inbox.isPaused}
                                                <PlayIcon />
                                            {:else}
                                                <PauseIcon />
                                            {/if}
                                        </Button>
                                        <Button
                                                variant="ghost"
                                                size="icon-sm"
                                                class="text-destructive hover:text-destructive"
                                                aria-label={$_("settings.emailAccounts.list.actions.delete")}
                                                title={$_("settings.emailAccounts.list.actions.delete")}
                                                onclick={() => (inboxToDelete = inbox)}
                                        >
                                            <TrashIcon />
                                        </Button>
                                    </div>
                                </Table.Cell>

                            </Table.Row>
                        {/each}
                    </Table.Body>
                </Table.Root>
            </div>
        </div>
    {:else}
        <Empty.Root class="h-full flex flex-col items-center justify-center border">
            <Empty.Header>
                <Empty.Media variant="icon">
                    <EnvelopeSimpleIcon />
                </Empty.Media>
                <Empty.Title>{$_("settings.emailAccounts.list.empty.title")}</Empty.Title>
                <Empty.Description>{$_("settings.emailAccounts.list.empty.description")}</Empty.Description>
            </Empty.Header>

            <Empty.Content>
                <div class="flex flex-row items-center gap-2">
                    <Button
                            class="w-fit"
                            onclick={() => (showNewEmailAccountDialog = true)}
                    >
                        <PlusIcon />
                        {$_("settings.emailAccounts.add")}
                    </Button>
                </div>
            </Empty.Content>
        </Empty.Root>
    {/if}
</div>

<!-- Neither dialog knows about this list, so each says what it did and this re-reads. -->
<NewEmailAccountDialog bind:open={showNewEmailAccountDialog} onCreated={load} />
<DeleteInboxDialog bind:inbox={inboxToDelete} onDeleted={load} onPaused={load} />
