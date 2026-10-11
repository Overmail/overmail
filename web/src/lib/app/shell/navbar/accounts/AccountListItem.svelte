<!--
    One row of the sidebar's account list: the link into the account's listing, its name as
    something that can be renamed in place, and the menu that fades in over it while the row is
    hovered or focused.

    The counterpart of `ViewListItem`, and built the same way. Renaming is the list's state, not
    this row's: one name is edited at a time. This one owns the input and reports what came of it.
-->
<script lang="ts">
    import {DotsThreeVerticalIcon, PencilSimpleIcon, TrashIcon, UserCircleIcon} from "phosphor-svelte";
    import {_} from "svelte-i18n";
    import {page} from "$app/state";
    import {Button} from "$lib/components/ui/button";
    import * as DropdownMenu from "$lib/components/ui/dropdown-menu";
    import {SidebarMenuButton, SidebarMenuTrailing} from "$lib/components/ui/sidebar";
    import RenameInput from "$lib/app/views/RenameInput.svelte";
    import {accountView} from "$lib/app/views/predefinedViews";
    import {inlineViewHref} from "$lib/app/views/viewPath";
    import {inboxLabel, type Inbox} from "$lib/repository/InboxRepository";

    const {
        account,
        isActive,
        renaming,
        onRenameStart,
        onRenameEnd,
        onEdit,
        onDelete,
    }: {
        account: Inbox;
        isActive: boolean;
        /** Whether the name is being edited -- the label is an input then. */
        renaming: boolean;
        onRenameStart: () => void;
        /** The end of the editing: the new name, or null when the account keeps the one it has. */
        onRenameEnd: (name: string | null) => void;
        onEdit: () => void;
        onDelete: () => void;
    } = $props();

    /** What the row says: the name, or the login of an account nobody named. */
    const label = $derived(inboxLabel(account));
</script>

<!-- pr-8 leaves the room the trailing slot overlays. -->
{#if renaming}
    <!-- The same shell as the link, so nothing about the row moves while it is renamed; a div
         inside it, because an input may not sit in a button. -->
    <SidebarMenuButton {isActive} class="pr-8">
        {#snippet child({props})}
            <div {...props}>
                <UserCircleIcon/>
                <!-- Opens on the login where there is no name yet: that is what stands in the
                     row, and leaving it as it is names nothing. -->
                <RenameInput
                        name={label}
                        onEnd={onRenameEnd}
                        label={$_("settings.emailAccounts.name.label")}
                        class="w-full min-w-0 bg-transparent outline-hidden"
                />
            </div>
        {/snippet}
    </SidebarMenuButton>
{:else}
    <SidebarMenuButton {isActive} class="pr-8">
        {#snippet child({props})}
            <a
                    href={inlineViewHref(accountView(account.id), page.url)}
                    {...props}
                    onclick={(event) => {
                        // The second click of a double click opens the name for editing; left to
                        // the router it would navigate again and take the focus off the input.
                        if (event.detail > 1) event.preventDefault();
                    }}
                    ondblclick={(event) => {
                        event.preventDefault();
                        onRenameStart();
                    }}
            >
                <UserCircleIcon/>
                <span>{label}</span>
            </a>
        {/snippet}
    </SidebarMenuButton>
{/if}

<SidebarMenuTrailing>
    {#snippet hover()}
        <DropdownMenu.Root>
            <DropdownMenu.Trigger>
                <!-- child, so the trigger *is* the button: one inside the other nests two
                     <button> elements, and both would answer the same key. -->
                {#snippet child({props})}
                    <Button
                            {...props}
                            variant="ghost"
                            size="icon-xs"
                            aria-label={$_("app.nav.accountActions", {values: {name: label}})}
                    >
                        <DotsThreeVerticalIcon/>
                    </Button>
                {/snippet}
            </DropdownMenu.Trigger>

            <DropdownMenu.Content align="start" class="w-52">
                <DropdownMenu.Item onclick={onEdit}>
                    <PencilSimpleIcon/>
                    {$_("settings.emailAccounts.list.actions.edit")}
                </DropdownMenu.Item>

                <!-- Always through the dialog: it says how many mails go with the account. -->
                <DropdownMenu.Item variant="destructive" onclick={onDelete}>
                    <TrashIcon/>
                    {$_("settings.emailAccounts.list.actions.delete")}
                </DropdownMenu.Item>
            </DropdownMenu.Content>
        </DropdownMenu.Root>
    {/snippet}
</SidebarMenuTrailing>
