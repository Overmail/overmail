<!--
    The bar above every page behind the sidebar: which page is open on the left, what can be done
    from anywhere on the right. A page adds controls of its own through setPageHeader.
-->
<script lang="ts">
    import * as Sidebar from "$lib/components/ui/sidebar";
    import {Separator} from "$lib/components/ui/separator";
    import OvermailAiPopover from "$lib/app/ai/popover/OvermailAiPopover.svelte";
    import {currentNavItem} from "$lib/app/shell/nav";
    import type {PageHeader} from "$lib/app/shell/pageHeader.svelte";
    import {page} from "$app/state";
    import {_} from "svelte-i18n";

    let {header}: {header: PageHeader} = $props();

    // Routes outside the menu -- none so far -- fall back to the product name over an empty bar.
    const item = $derived(currentNavItem(page.url.pathname));

    let assistantOpen = $state(false);
</script>

<header
        class="flex shrink-0 items-center gap-2 border-b transition-[width,height] ease-linear h-12"
>
    <div class="flex w-full items-center gap-1 px-4 lg:gap-2 lg:px-6">
        <Sidebar.Trigger class="-ms-1" />
        <Separator orientation="vertical" class="mx-2 data-[orientation=vertical]:h-4" />
        <h1 class="text-base font-medium">{item ? $_(item.key) : $_('app.name')}</h1>
        <div class="ms-auto flex items-center gap-2">
            {@render header.actions?.()}
            <OvermailAiPopover
                    bind:open={assistantOpen}
                    onCloseFocus={header.restoreFocus ?? undefined}
            />
        </div>
    </div>
</header>
