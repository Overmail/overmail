<!--
    The sidebar's mail accounts: one row each, a link into the listing of that account.

    A row works the way a view's does, see `ViewList` -- a double click edits the name in place,
    and a menu fades in over it while it is hovered. What the menu opens are the dialogs the
    settings have for the same account; this list only decides which one they are about.

    Read rather than subscribed to: nothing announces a changed account. The list is read when
    the sidebar appears and again after anything done here changed it.
-->
<script lang="ts">
    import {onMount} from "svelte";
    import {goto} from "$app/navigation";
    import {page} from "$app/state";
    import {
        SidebarGroup,
        SidebarGroupContent,
        SidebarMenu,
        SidebarMenuItem,
    } from "$lib/components/ui/sidebar";
    import DeleteInboxDialog from "$lib/app/settings/email-accounts/DeleteInboxDialog.svelte";
    import EditEmailAccountDialog from "$lib/app/settings/email-accounts/EditEmailAccountDialog.svelte";
    import {accountView} from "$lib/app/views/predefinedViews";
    import {inlineViewParam, VIEW_PARAM} from "$lib/app/views/viewPath";
    import type {Inbox} from "$lib/repository/InboxRepository";
    import {useRepositories} from "$lib/repository/repositories";
    import AccountListItem from "./AccountListItem.svelte";

    const {inboxes} = useRepositories();

    let accounts = $state<Inbox[]>([]);

    /** Which account's name is being edited, if any: one at a time, like the views. */
    let renamingId: string | null = $state(null);

    /** The accounts the dialogs are open for; null while they are closed. */
    let editing: Inbox | null = $state(null);
    let deleting: Inbox | null = $state(null);

    /** The account the delete dialog last closed on, for what follows a delete. */
    let closed: Inbox | null = null;

    // In the browser: the list needs the session.
    onMount(load);

    function load() {
        inboxes.list().then((list) => (accounts = list)).catch((error) => console.error(error));
    }

    /** Whether the url is showing the listing of [account]. */
    function isOpen(account: Inbox): boolean {
        return (
            page.url.pathname === "/" &&
            page.url.searchParams.get(VIEW_PARAM) === inlineViewParam(accountView(account.id))
        );
    }

    function finishRename(account: Inbox, name: string | null) {
        renamingId = null;
        // Null is the account keeping its name, see `renamedTo` -- nothing to ask the server for.
        if (name === null) return;

        inboxes
            .rename(account.id, name)
            // Into the row that is on screen: the list is this component's own, and nothing
            // else would bring the new name here.
            .then((stored) => (account.name = stored))
            .catch((error) => console.error(error));
    }

    /**
     * After a delete: the row goes, and so does the listing if it was the one on screen -- it
     * would be the listing of an account that no longer exists, and stay empty.
     */
    async function afterDelete(account: Inbox) {
        const wasOpen = isOpen(account);
        load();
        if (wasOpen) await goto("/", {replaceState: true});
    }
</script>

<!-- Nothing while there is no account: an empty group would only be a gap in the sidebar. -->
{#if accounts.length > 0}
    <SidebarGroup>
        <SidebarGroupContent>
            <SidebarMenu>
                {#each accounts as account (account.id)}
                    <SidebarMenuItem>
                        <AccountListItem
                                {account}
                                isActive={isOpen(account)}
                                renaming={renamingId === account.id}
                                onRenameStart={() => (renamingId = account.id)}
                                onRenameEnd={(name) => finishRename(account, name)}
                                onEdit={() => (editing = account)}
                                onDelete={() => (deleting = account)}
                        />
                    </SidebarMenuItem>
                {/each}
            </SidebarMenu>
        </SidebarGroupContent>
    </SidebarGroup>
{/if}

<!-- One of each for the whole list: which account they ask about is what opens them. The
     account is kept past the dialog closing it, because that is when it is known what to do. -->
<EditEmailAccountDialog bind:inbox={editing} onSaved={load}/>
<DeleteInboxDialog
        bind:inbox={
            () => deleting,
            (next) => {
                // The dialog only ever sets null, and what it closed on is still needed below.
                if (next === null) closed = deleting;
                deleting = next;
            }
        }
        onDeleted={() => closed && afterDelete(closed)}
        onPaused={load}
/>
