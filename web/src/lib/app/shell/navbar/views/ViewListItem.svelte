<!--
    One row of the sidebar's view list: the link into the view, its name as something that can be
    renamed in place, and the menu that fades in over it while the row is hovered or focused.

    The `<li>` around it stays in `ViewList.svelte`: `animate:flip` only works on an element that
    is a direct child of the keyed each block, and the drag sits on the same element. Which is
    also why this is a `SidebarMenuButton` and not a `SidebarMenuEntry` -- the entry brings its
    own `<li>`, and an `<li>` in an `<li>` is not a list.

    Renaming is the list's state, not this row's: only one row is renamed at a time, and the drag
    has to be off while it is (see `ViewList`). This one owns the input and reports what came of
    it, nothing more.
-->
<script lang="ts">
    import {DotsThreeVerticalIcon, ListIcon, PencilSimpleIcon, TrashIcon} from "phosphor-svelte";
    import {_} from "svelte-i18n";
    import {page} from "$app/state";
    import {Button} from "$lib/components/ui/button";
    import * as DropdownMenu from "$lib/components/ui/dropdown-menu";
    import {SidebarMenuButton, SidebarMenuTrailing} from "$lib/components/ui/sidebar";
    import RenameInput from "$lib/app/views/RenameInput.svelte";
    import {viewHref} from "$lib/app/views/viewPath";
    import type {View} from "$lib/repository/ViewSocket";

    const {
        view,
        isActive,
        renaming,
        onRenameStart,
        onRenameEnd,
        onDelete,
    }: {
        view: View;
        isActive: boolean;
        /** Whether the name is being edited -- the label is an input then. */
        renaming: boolean;
        onRenameStart: () => void;
        /**
         * The end of the editing: the new name, or null when the view keeps the one it has -- an
         * empty box, Escape, or the same name typed again, see [renamedTo].
         */
        onRenameEnd: (name: string | null) => void;
        /** [immediately] leaves out the confirmation; shift asks for that, see the menu below. */
        onDelete: (immediately: boolean) => void;
    } = $props();

    /** Whether the row's menu is open, which is while the shift key is worth watching. */
    let menuOpen = $state(false);

    /** Whether shift is down: the delete then takes the view without asking first. */
    let shiftHeld = $state(false);

    /** Set when the menu's rename was picked, so the closing menu leaves the input its focus. */
    let renameFromMenu = false;


    $effect(() => {
        if (!menuOpen) {
            shiftHeld = false;
            return;
        }

        const read = (event: KeyboardEvent) => (shiftHeld = event.shiftKey);
        const clear = () => (shiftHeld = false);

        // On the window rather than on the menu: the key is held anywhere, and a window that
        // loses the focus never sees the keyup that let go of it.
        window.addEventListener("keydown", read);
        window.addEventListener("keyup", read);
        window.addEventListener("blur", clear);

        return () => {
            window.removeEventListener("keydown", read);
            window.removeEventListener("keyup", read);
            window.removeEventListener("blur", clear);
        };
    });

</script>

<!-- pr-8 leaves the room the trailing slot overlays. -->
{#if renaming}
    <!-- The same shell as the link, so nothing about the row moves while it is renamed; a div
         inside it, because an input may not sit in a button. -->
    <SidebarMenuButton {isActive} class="pr-8">
        {#snippet child({props})}
            <div {...props}>
                <ListIcon/>
                <RenameInput
                        name={view.name}
                        onEnd={onRenameEnd}
                        label={$_("views.nameLabel")}
                        class="w-full min-w-0 bg-transparent outline-hidden"
                />
            </div>
        {/snippet}
    </SidebarMenuButton>
{:else}
    <SidebarMenuButton {isActive} class="pr-8">
        <!-- An anchor, so a view can be opened in a new tab and its address copied; the router
             handles the click. Not draggable itself: a link drags its url, and the row around it
             drags the view. -->
        {#snippet child({props})}
            <a
                    href={viewHref(view.id, view.name, page.url)}
                    draggable="false"
                    {...props}
                    onclick={(event) => {
                        // The second click of a double click is not a second navigation: it
                        // opens the name for editing, and the first one has already put the view
                        // on screen. Left to the router it would navigate again and end the
                        // editing with the focus reset that follows a navigation.
                        if (event.detail > 1) event.preventDefault();
                    }}
                    ondblclick={(event) => {
                        event.preventDefault();
                        onRenameStart();
                    }}
            >
                <ListIcon/>
                <span>{view.name}</span>
            </a>
        {/snippet}
    </SidebarMenuButton>
{/if}

<SidebarMenuTrailing>
    {#snippet hover()}
        <DropdownMenu.Root bind:open={menuOpen}>
            <DropdownMenu.Trigger>
                <!-- child, so the trigger *is* the button: one inside the other nests two
                     <button> elements, and both would answer the same key. -->
                {#snippet child({props})}
                    <Button
                            {...props}
                            variant="ghost"
                            size="icon-xs"
                            aria-label="Aktionen für {view.name}"
                            onmousedown={(event) => (shiftHeld = event.shiftKey)}
                    >
                        <DotsThreeVerticalIcon/>
                    </Button>
                {/snippet}
            </DropdownMenu.Trigger>

            <DropdownMenu.Content
                    align="start"
                    class="w-48"
                    onCloseAutoFocus={(event) => {
                        // The menu hands the focus back to its button, which would take it off
                        // the input the rename has just put into the row.
                        if (!renameFromMenu) return;

                        renameFromMenu = false;
                        event.preventDefault();
                    }}
            >
                <DropdownMenu.Item
                        onclick={() => {
                            renameFromMenu = true;
                            onRenameStart();
                        }}
                >
                    <PencilSimpleIcon/>
                    Umbenennen
                </DropdownMenu.Item>

                <!-- Shift is the way past the confirmation, and the label says so while it is
                     held: the entry has to look like what it is about to do. -->
                <DropdownMenu.Item
                        variant="destructive"
                        onclick={(event) => onDelete(event.shiftKey || shiftHeld)}
                >
                    <TrashIcon/>
                    {shiftHeld ? "Sofort löschen" : "Löschen"}
                </DropdownMenu.Item>
            </DropdownMenu.Content>
        </DropdownMenu.Root>
    {/snippet}
</SidebarMenuTrailing>
