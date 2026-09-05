<script lang="ts">
    import * as Sidebar from "$lib/components/ui/sidebar";
    import {Separator} from "$lib/components/ui/separator";
    import {currentNavItem} from "$lib/app/shell/nav";
    import type {HeaderCover, PageHeader} from "$lib/app/shell/pageHeader.svelte";
    import {page} from "$app/state";
    import {_} from "svelte-i18n";
    import {Button} from "$lib/components/ui/button";
    import {SidebarSimpleIcon} from "phosphor-svelte";
    import * as Avatar from "$lib/components/ui/avatar";
    import {useRepositories} from "$lib/repository/repositories";
    import type {CurrentUser} from "$lib/repository/CurrentUserRepository";
    import {onMount} from "svelte";
    import {initials} from "$lib/utils.ts";
    import {goto} from "$app/navigation";

    let {
        header,
        sidebarOpen = $bindable(false),
    }: {
        header: PageHeader,
        sidebarOpen: boolean,
    } = $props();

    // Routes outside the menu -- none so far -- fall back to the product name over an empty bar.
    const item = $derived(currentNavItem(page.url.pathname));

    /**
     * The room the cover takes at the end of the bar, opened and closed with it.
     *
     * A transition rather than a CSS one on the box itself, running the cover's own duration and
     * easing: Svelte builds the keyframes for this the same way it builds them for the panel, so
     * the two are one movement instead of two curves that only agree at the ends. Anything hand
     * -rolled drifts -- of the curves CSS has a name for, the closest is a hundred pixels off the
     * panel's edge halfway through, and the nearest `cubic-bezier` still some eight.
     *
     * It also lands the same way on a page that opens with the panel already there: transitions
     * do not play on the first render, so neither of them slides in.
     */
    function makeRoom(_node: HTMLElement, cover: HeaderCover) {
        return {
            duration: cover.motion.durationMs,
            easing: cover.motion.easing,
            css: (t: number) => `width: ${t * cover.width}px`,
        };
    }

    const {currentUser} = useRepositories();

    let user = $state<CurrentUser | null>(null);

    onMount(() => {
        currentUser.get().then((result) => (user = result));
    });

</script>

<!-- Sticky: the page is one scroll container, so without this the bar with the page's name and
     the assistant scrolls away with the greeting. Above what the pages stick themselves, and with
     a background of its own -- rows would otherwise run through it. -->
<header
        class="bg-background sticky top-0 z-30 flex shrink-0 items-center gap-2 transition-[width,height] ease-linear h-12"
>
    <div class="flex w-full items-center gap-1 px-4 lg:gap-2 lg:px-6">
        <Sidebar.Trigger class="-ms-1" />
        <Separator orientation="vertical" class="mx-2 data-[orientation=vertical]:h-4" />
        <h1 class="text-base font-medium">{item ? $_(item.key) : $_('app.name')}</h1>
        <!-- The controls and the room next to them are one item of the bar, not two: the bar
             sets a gap between its items, and a second item appearing would step them over by it
             the moment the panel opens on top of the movement below. -->
        <div class="ms-auto flex items-center">
            <div class="flex items-center gap-2">
                {@render header.actions?.()}

                {#if user}

                    <Button
                            size="icon-sm"
                            variant="ghost"
                            onclick={() => {
                                const url = new URL(page.url);
                                url.searchParams.set("settings", "");
                                goto(url, {replaceState: true, noScroll: true});
                            }}
                    >
                        <Avatar.Root size="sm">
                            <Avatar.Fallback>{initials(user.firstname + " " + user.lastname)}</Avatar.Fallback>
                        </Avatar.Root>
                    </Button>
                {/if}

                <Button
                        size="icon-sm"
                        variant={sidebarOpen ? "secondary" : "ghost"}
                        onclick={() => sidebarOpen = !sidebarOpen}
                >
                    <SidebarSimpleIcon class="rotate-180" />
                </Button>
            </div>

            <!-- What keeps the controls beside it out from under whatever the page has laid over
                 this end of the bar: the mail panel is fixed and paints across the header rather
                 than pushing it aside, and the trigger under it would be neither visible nor
                 clickable. A box rather than a margin, so the end of the bar is the end of the
                 bar in both writing directions. -->
            {#if header.coveredEnd.present}
                <div
                        aria-hidden="true"
                        class="shrink-0"
                        style:width="{header.coveredEnd.width}px"
                        transition:makeRoom={header.coveredEnd}
                ></div>
            {/if}
        </div>
    </div>
</header>
