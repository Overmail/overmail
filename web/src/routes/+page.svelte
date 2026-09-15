<script lang="ts">
    import {_} from "svelte-i18n";
    import {cubicOut} from "svelte/easing";
    import {FloppyDiskIcon} from "phosphor-svelte";
    import HomeGreeting from "$lib/app/home/HomeGreeting.svelte";
    import {useRepositories} from "$lib/repository/repositories";
    import {Button} from "$lib/components/ui/button";
    import {Skeleton} from "$lib/components/ui/skeleton";
    import EmailGraph from "$lib/app/home/EmailGraph.svelte";
    import MailTable from "$lib/app/mails/MailTable.svelte";
    import type {ViewSettings} from "$lib/app/views/viewSettings";
    import {INBOX_VIEW, predefinedView, isPredefinedViewId} from "$lib/app/views/predefinedViews";
    import {openViewId, viewUrl} from "$lib/app/views/viewPath";
    import {askToName, takeNameRequest, viewToName} from "$lib/app/views/newView.svelte";
    import {viewKey} from "$lib/app/mails/MailListViewModel.svelte";
    import {page} from "$app/state";
    import {goto} from "$app/navigation";
    import {onMount, untrack} from "svelte";

    const {home} = useRepositories();

    // The socket is up while this page is: the effect's teardown releases it.
    $effect(() => home.connect());

    let currentUserName = $state<string | null>(null);
    let title = $derived.by(() => {
        let result = viewName;
        if (home.mailboxCount !== null) result += " (" + home.mailboxCount + ")";
        if (currentUserName) result += " • " + currentUserName;

        return result;
    })
    const {currentUser} = useRepositories();

    onMount(() => {
        currentUser.get().then((user) => {
            if (user) currentUserName = user.firstname + " " + user.lastname;
        })
    })

    const {views} = useRepositories();

    // The socket is up while this page is, so a view renamed or changed in another tab is what
    // this one shows as well.
    $effect(() => views.connect());

    /** Which view the url is asking for: a name the app brings, an id somebody stored, or none. */
    const viewId = $derived(openViewId(page.url));

    /** The stored view the url points at, or null while it is one of the app's own -- or not here. */
    const stored = $derived(
        viewId === null || isPredefinedViewId(viewId)
            ? null
            : (views.views.find((view) => view.id === viewId) ?? null)
    );

    /**
     * The view as it is saved: what the page starts from, and what it goes back to when the url
     * points somewhere else. Nothing asked for is the inbox.
     */
    const saved = $derived<ViewSettings>(
        stored === null
            ? predefinedView(viewId).settings
            : {groupings: stored.groupings, filter: stored.filter, sorting: stored.sorting}
    );

    /**
     * The view this page is showing, which is [saved] and whatever has been changed about it
     * since. The bar above the rows writes into it; nothing is saved yet, so the changes last as
     * long as the page does.
     */
    let current = $state<ViewSettings>(predefinedView(null).settings);

    /**
     * What [current] was last filled from: the view the url names, in the state the server has
     * it. Changing either fills it again; changing the settings on screen does not, or an edit
     * would be undone by the very effect that noticed it.
     */
    let filledFrom = $state<string | null>(null);

    $effect(() => {
        const key = `${viewId ?? INBOX_VIEW}|${viewKey(saved)}`;
        if (filledFrom === key) return;

        filledFrom = key;
        // Through a snapshot: a stored view's settings are state of the repository, and a plain
        // `structuredClone` of one is a proxy being cloned, which throws.
        current = $state.snapshot(saved) as ViewSettings;
    });

    /** What the tab is called: the view, because that is what the page is showing. */
    const viewName = $derived(stored?.name ?? $_(predefinedView(viewId).label));

    /**
     * Whether this is the listing the app opens on. The inbox is what the bare url shows, so it
     * is both "nothing asked for" and the view that names itself.
     */
    const isInbox = $derived(viewId === null || viewId === INBOX_VIEW);

    /**
     * Whether the view the app brought has been changed on screen -- which is the only thing
     * there is to offer saving. A stored view is saved already; what changing one of those does
     * is its own question.
     */
    const changed = $derived(stored === null && viewKey(current) !== viewKey(saved));

    let saving = $state(false);

    /** How long a change waits before it is written, so a handful of clicks is one request. */
    const SAVE_DELAY = 600;

    /**
     * A view of one's own keeps what is changed about it.
     *
     * Nothing to press: it is the same as the rename in the sidebar, which is saved the moment it
     * is done. The offer to save is for the views the app brings, and those cannot be changed --
     * that is what makes them the app's.
     */
    $effect(() => {
        const view = stored;
        if (view === null) return;

        const settings = $state.snapshot(current) as ViewSettings;
        if (viewKey(settings) === viewKey(saved)) return;

        const timer = setTimeout(() => {
            views.update(view.id, {settings}).catch((error) => console.error(error));
        }, SAVE_DELAY);

        return () => clearTimeout(timer);
    });

    /** Whether the heading over the listing is being edited. */
    let renamingName = $state(false);

    // A view that was just created is waiting for its name, whichever button made it -- see
    // `newView`. It is announced over the socket, so the editor can only open once it is here.
    // Untracked, because opening it is a write this would otherwise read back.
    $effect(() => {
        const id = viewToName();
        if (id === null || stored?.id !== id) return;

        untrack(() => {
            takeNameRequest(id);
            renamingName = true;
        });
    });

    /**
     * Keeps the settings on screen as a view of one's own.
     *
     * A view the app brings cannot be changed, so what a change to one of them is worth is a new
     * view -- created empty, given these settings, and opened straight away. Its name is the one
     * the server generated; renaming it is a double click in the sidebar.
     */
    async function saveAsView() {
        if (saving) return;
        saving = true;

        try {
            const created = await views.create();
            await views.update(created.id, {settings: $state.snapshot(current)});
            // Naming it is the next thing somebody wants: the server's "Neue Ansicht 3" is a
            // placeholder. The editor opens on the heading over the listing rather than on the
            // sidebar's row -- that is where the view was just put together.
            askToName(created.id);
            await goto(viewUrl(created.id, created.name, page.url));
        } catch (error) {
            console.error(error);
        } finally {
            saving = false;
        }
    }

    /** Renames the stored view this page is showing. */
    function rename(name: string) {
        const view = stored;
        if (view === null) return;

        views.update(view.id, {name}).catch((error) => console.error(error));
    }

    /**
     * Out of the way, and back in again: the height collapses while the block fades and lifts.
     *
     * Both at once, because a block that only fades leaves a hole and then drops the table up a
     * screen's worth once it is gone. `slide` and `fade` would be two transitions on one element,
     * which Svelte does not take -- so they are written as one.
     */
    function collapse(node: Element, {duration}: {duration: number}) {
        const style = getComputedStyle(node);
        const height = parseFloat(style.height);
        const paddingTop = parseFloat(style.paddingTop);
        const paddingBottom = parseFloat(style.paddingBottom);

        return {
            duration,
            easing: cubicOut,
            css: (t: number, u: number) =>
                `overflow: hidden; height: ${t * height}px;` +
                `padding-top: ${t * paddingTop}px; padding-bottom: ${t * paddingBottom}px;` +
                `opacity: ${t}; translate: 0 ${-u * 8}px;`,
        };
    }
</script>

<svelte:head>
    <title>{title}</title>
</svelte:head>

<div class="flex flex-col">
    <!-- The greeting and the heatmap belong to the mailbox itself, not to a listing somebody put
         together: they are there on the inbox and nowhere else. -->
    {#if isInbox}
        <div
                class="flex flex-row flex-wrap px-16 pt-16 pb-12 gap-x-16 gap-y-8"
                transition:collapse={{duration: 260}}
        >
            <div class="flex flex-col gap-2">
                <HomeGreeting/>
                {#if home.mailboxCount !== null}
                    <h2 class="text-muted-foreground">
                        {$_("home.mailbox.count", {values: {count: home.mailboxCount}})}
                    </h2>
                {:else}
                    <Skeleton class="h-5 w-56"/>
                {/if}
            </div>

            <EmailGraph/>
        </div>
    {/if}

    <!-- The air above the table belongs to the greeting, so it goes out with it: the padding is
         part of the block that collapses. -->
    <div class="px-4 pb-16">
        <MailTable
                bind:view={current}
                name={stored?.name ?? null}
                onRename={stored === null ? undefined : rename}
                bind:renaming={renamingName}
                actions={viewActions}
        />
    </div>
</div>

<!-- Ghost, and only while there is something to keep: it is an offer beside the filters, not a
     button somebody has to answer. -->
{#snippet viewActions()}
    {#if changed}
        <Button
                variant="ghost"
                size="sm"
                onclick={saveAsView}
                disabled={saving}
                class="text-muted-foreground h-auto gap-1.5 rounded-full px-2 py-0.5 text-xs font-normal"
        >
            <FloppyDiskIcon class="size-3.5"/>
            {$_(saving ? "views.saving" : "views.save")}
        </Button>
    {/if}
{/snippet}
