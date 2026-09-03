<script lang="ts">
    import * as Sidebar from "$lib/components/ui/sidebar";
    import {Separator} from "$lib/components/ui/separator";
    import {currentNavItem} from "$lib/app/shell/nav";
    import type {PageHeader} from "$lib/app/shell/pageHeader.svelte";
    import {page} from "$app/state";
    import {_} from "svelte-i18n";
    import {Button} from "$lib/components/ui/button";
    import {SidebarSimpleIcon} from "phosphor-svelte";

    let {
        header,
        sidebarOpen = $bindable(false),
    }: {
        header: PageHeader,
        sidebarOpen: boolean,
    } = $props();

    // Routes outside the menu -- none so far -- fall back to the product name over an empty bar.
    const item = $derived(currentNavItem(page.url.pathname));
</script>

<header
        class="flex shrink-0 items-center gap-2 transition-[width,height] ease-linear h-12"
>
    <div class="flex w-full items-center gap-1 px-4 lg:gap-2 lg:px-6">
        <Sidebar.Trigger class="-ms-1" />
        <Separator orientation="vertical" class="mx-2 data-[orientation=vertical]:h-4" />
        <h1 class="text-base font-medium">{item ? $_(item.key) : $_('app.name')}</h1>
        <div class="ms-auto flex items-center gap-2">
            {@render header.actions?.()}

            <Button
                    size="icon-sm"
                    variant={sidebarOpen ? "secondary" : "ghost"}
                    onclick={() => sidebarOpen = !sidebarOpen}
            >
                <SidebarSimpleIcon class="rotate-180" />
            </Button>
        </div>
    </div>
</header>
