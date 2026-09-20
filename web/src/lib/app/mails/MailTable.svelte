<!--
    Every mail in the mailbox, newest first, as a windowed table.

    Two sources, the same split as the stack: the list view model says which mail sits at which
    row, and the email repository says what each of them is and keeps it current. Only the rows
    near the viewport are in the DOM, and only those are subscribed.
-->
<script lang="ts">
    import {FlexRender, createTable} from "@tanstack/svelte-table";
    import {createHotkey} from "@tanstack/svelte-hotkeys";
    import {_} from "svelte-i18n";
    import {untrack, type Snippet} from "svelte";
    import {page} from "$app/state";
    import {goto} from "$app/navigation";
    import * as Table from "$lib/components/ui/table";
    import {Button} from "$lib/components/ui/button";
    import {Toggle} from "$lib/components/ui/toggle";
    import {ArchiveIcon, UserIcon, UsersIcon} from "phosphor-svelte";
    import {createWindowVirtualizer} from "$lib/hooks/virtualizer.svelte";
    import {cn} from "$lib/utils";
    import {useRepositories} from "$lib/repository/repositories";
    import type {EmailMeta} from "$lib/repository/EmailRepository.svelte";
    import type {ViewFilter} from "$lib/repository/ViewSocket";
    import type {ReadState} from "$lib/app/filters/IsUnreadFilter.svelte";
    import {readStateOf} from "$lib/app/filters/readState";
    import {type ViewSettings} from "$lib/app/views/viewSettings";
    import {COLUMN_WIDTHS, GHOST_SHAPES, columns, features, type MailTableRow} from "./columns";
    import {MailListViewModel, type MailStep} from "./MailListViewModel.svelte";
    import type {MailGroupNode} from "./mailLayout";
    import {MailSelection, setMailSelection} from "./mailSelection";
    import {EMAIL_PARAM, FROM_MAIL_LIST, FROM_PARAM, emailPath, emailSlug, parseEmailId} from "./emailPath";
    import MailGhostCell from "./table/MailGhostCell.svelte";
    import MailGroupHeader from "./table/MailGroupHeader.svelte";
    import RenameInput from "$lib/app/views/RenameInput.svelte";
    import MailDetailPanel, {PANEL_COVER} from "./detail_panel/MailDetailPanel.svelte";
    import {coverHeaderEnd} from "$lib/app/shell/pageHeader.svelte";
    import MailRowPreview from "./MailRowPreview.svelte";
    import MailSelectionBar from "./MailSelectionBar.svelte";
    import MailsEmpty from "./MailsEmpty.svelte";
    import IsUnreadFilter from "$lib/app/filters/IsUnreadFilter.svelte";
    import LabelFilter from "$lib/app/labels/LabelFilter.svelte";
    import SenderFilter from "$lib/app/senders/SenderFilter.svelte";
    import InboxFilter from "$lib/app/filters/InboxFilter.svelte";
    import GroupingSettings from "$lib/app/views/GroupingSettings.svelte";
    import IsArchiveFilter from "$lib/app/filters/IsArchiveFilter.svelte";

    /**
     * Not estimates but the heights: every row is one clipped line, so the scrollbar is sized
     * from these two numbers rather than from anything measured.
     *
     * Which is also the catch -- a row that renders taller than its number here drifts the whole
     * list. [HEADER_HEIGHT] is the box MailGroupHeader takes: its own line plus the space it sets
     * above and below itself, so the two have to be changed together.
     */
    const ROW_HEIGHT = 40;

    /**
     * What a header row takes, by level. The outermost one brings the air that sets one stretch
     * off from the one before it; a header under it is a smaller step, or the list would be more
     * space than mail. Both have to match what `MailGroupHeader` actually draws, or the
     * virtualizer sizes the rows from the wrong number and the list drifts.
     */
    const HEADER_HEIGHT = 60;
    const SUB_HEADER_HEIGHT = 38;

    /**
     * How far a mail row sits from the left, by how deep the group holding it is.
     *
     * One step per level, the same step the headers take: a row ends up exactly under the header
     * it belongs to -- its avatar in the column that header's own box stands in. An ungrouped
     * listing takes no step at all, because there is no header to line up with.
     */
    const INDENT_STEP_REM = 0.75;

    const mailIndent = (depth: number) =>
        depth === 0 ? undefined : `padding-left: ${depth * INDENT_STEP_REM}rem`;

    /** Where the bar above the rows comes to rest: the app header's height, its own `top-12`. */
    const BAR_TOP = 48;

    /** What one pinned header takes: its line and the step under it, without the air above it. */
    const PINNED_HEIGHT = 21;

    /**
     * The air over the pinned headers.
     *
     * Not decoration: a header's text is centred on the top edge of its own box and hangs half a
     * line above it (which is what the space above it is for). Pinned flush under the bar, that
     * half would be behind the bar -- the header would read as a white strip with no text in it.
     */
    const PINNED_PAD = 10;

    /** The air a header keeps above itself, which is where its line actually begins. */
    const headerTopMargin = (level: number) => (level === 0 ? 40 : 16);

    /** How many rows around the viewport are held and kept up to date. */
    const OVERSCAN = 12;

    /**
     * Stand-ins for the length nobody knows yet. Before the first page there would otherwise be
     * no rows at all and the empty state would flash.
     */
    const PLACEHOLDER_ROWS = 8;

    let {view = $bindable(), name = null, onRename, renaming = $bindable(false), actions}: {
        /**
         * The listing this table is: what it leaves out, how it is cut up, what orders it. The
         * bar above the rows writes into it, so a caller that wants the changes kept binds it to
         * something that keeps them -- the mailbox binds it to state of its own, which is gone
         * with the page.
         */
        view: ViewSettings;
        /**
         * What this listing is called. Null is a listing that is not a view somebody made, and
         * then the heading says what it holds instead -- the mailbox, or everything in it.
         */
        name?: string | null;
        /**
         * Renames it. Given only where the name belongs to somebody: a view the app brings cannot
         * be renamed, and without this the heading is a heading and not an editor.
         */
        onRename?: (name: string) => void;
        /** Whether the name is being edited. Bindable, so a caller can open the editor itself. */
        renaming?: boolean;
        /**
         * What the caller puts at the end of the filter row. The table knows what was changed
         * about the view, not what can be done about it -- saving it is the page's business,
         * because only the page knows which view this is.
         */
        actions?: Snippet;
    } = $props();

    const {mails} = useRepositories();
    const list = new MailListViewModel(mails);

    /** The listing is the view: what it leaves out, how it is cut up, what orders it. */
    $effect(() => list.setView(view));

    /**
     * Whether the listing is more than the mailbox. What the title says, and what a row that left
     * it is measured against -- the filter as a whole is the server's business, this is the one
     * part of it the table has to know itself.
     */
    const allMails = $derived(
        view.filter.archivedState === null ||
            view.filter.archivedState.some((state) => state !== "Unarchive")
    );

    /**
     * What each chip reads and writes: the view's filter itself.
     *
     * No state of its own in between. A chip that kept its own copy would have to be told when
     * the view changed under it -- and the one that was here wrote its copy back over every view
     * this page was handed, which is how "sent" came out as the inbox.
     */
    const withFilter = (change: Partial<ViewFilter>) => {
        view.filter = {...view.filter, ...change};
    };

    /** Ids for a view, or null where nothing is picked -- no restriction, not an empty set. */
    const idsOrNull = (ids: string[]) => (ids.length === 0 ? null : ids);

    /** The end of the editing: null is the name staying as it is, see `renamedTo`. */
    function endRename(next: string | null) {
        renaming = false;
        if (next !== null) onRename?.(next);
    }

    /** The read states a chip shows for what the filter says, and back again. */
    const readStates = (state: boolean | null): ReadState[] =>
        state === null ? [] : state ? ["read"] : ["unread"];

    /**
     * Which mails are ticked. Handed to the cells through the context rather than as a prop: what
     * a column declares is a component and the mail it gets, nothing else (see columns.ts).
     */
    const selection = new MailSelection();
    setMailSelection(selection);

    // Switching the list switches which mails are on the table, and a tick that is no longer on
    // screen is one nobody can take back. Untracked because clearing writes the very set a row
    // reads -- tracked, this would be an effect that re-runs itself.
    $effect(() => {
        void view.filter;
        untrack(() => selection.clear());
    });

    /**
     * Whether [mail] is still in the list it was picked in. The mailbox is what has not been put
     * away; everything that ever arrived is everything but spam -- the two scopes, see MailScope
     * on the server.
     */
    const stillListed = (mail: EmailMeta) =>
        allMails ? mail.archiveState !== "spam" : mail.archiveState === "unarchive";

    // A mail that left the list is not picked any more: it was archived from the panel, or filed
    // by the assistant, and a tick nobody can see is one nobody can take back.
    //
    // Only the mails the repository holds are looked at -- the rest are not on screen either, and
    // what put them away would have gone through here as well. Writing bumps the set this reads,
    // so this runs once more and then finds nothing left to drop.
    $effect(() => {
        const gone = selection.ids.filter((id) => {
            const mail = mails.peek(id).value;
            return mail !== null && !stillListed(mail);
        });

        if (gone.length > 0) untrack(() => selection.setAll(gone, false));
    });

    // Nothing here listens for a mail arriving or being archived: the listing socket sends the
    // shape and the pages again on its own when that happens, so the rows are new before anything
    // on this side could have noticed to ask. See MailListViewModel.

    /**
     * Which mail is open in the panel, which is what `?email=` says and nothing else -- so a
     * reload, a link and the back button all land on the same mail, and there is no second copy
     * of it to keep in step.
     */
    const openId = $derived(parseEmailId(page.url.searchParams.get(EMAIL_PARAM)));

    // The panel is fixed and pinned to the same edge the header's controls sit at, so it covers
    // them. Declared from here rather than from the panel itself: this runs the moment the mail
    // is opened or closed, and the header then moves along with the panel sliding rather than
    // after it has finished.
    coverHeaderEnd(PANEL_COVER, () => openId !== null);

    /*
     * Escape ends selection mode, and only then: it is the way out for somebody who ticked a row
     * by accident. While a mail is open the panel owns the key -- what the reader means by it
     * with a mail on screen is "close the mail", and the next Escape then drops the selection.
     */
    createHotkey(
        "Escape",
        () => selection.clear(),
        () => ({enabled: selection.active && openId === null, ignoreInputs: true})
    );

    /**
     * Opens a mail beside the list.
     *
     * Through [goto] rather than through shallow routing: `pushState` changes the address bar and
     * `page.state`, but it leaves `page.url` on the last navigation -- and the url is what says
     * which mail is open here, so the panel would not have noticed a switch or a close. Nothing
     * loads from it either way: no load function of this route reads the query.
     *
     * A row that was clicked pushes, so the back button closes the panel again; stepping and
     * closing replace, or a walk down the list would be a walk back up through the history.
     */
    function showEmail(id: string, history: "push" | "replace") {
        // Clicking the row that is already open is not another entry in the history.
        if (id === openId) return;

        const url = new URL(page.url);
        url.searchParams.set(EMAIL_PARAM, emailSlug(id, mails.peek(id).value?.subject));

        void navigate(url, history);
    }

    /**
     * Leaves the list for the mail's own page, in this tab and as an entry of its own in the
     * history -- so the back button of the browser, and the one that page shows because of the
     * query, both land back on the list.
     */
    function openEmailPage(id: string, subject: string | null | undefined) {
        const url = new URL(emailPath(id, subject), page.url);
        url.searchParams.set(FROM_PARAM, FROM_MAIL_LIST);

        // Through goto with its defaults, not through [navigate]: this one is a page change, so
        // the scroll position and the focus are the router's business after all.
        void goto(url);
    }

    function closeEmail() {
        const url = new URL(page.url);
        url.searchParams.delete(EMAIL_PARAM);

        void navigate(url, "replace");
    }

    // Neither the scroll position nor the focus is the router's business here: what changes is a
    // panel beside a list somebody is in the middle of, not the page.
    const navigate = (url: URL, history: "push" | "replace") =>
        goto(url, {noScroll: true, keepFocus: true, replaceState: history === "replace"});

    /**
     * One mail up or down, as the table has them ordered -- that is what makes the buttons
     * predictable, and it is why the listing answers this rather than the panel.
     *
     * Nothing happens when the page that mail sits on is not here yet; the step asked for it, so
     * the next click on the same button goes through.
     */
    function stepEmail(step: MailStep) {
        if (openId === null) return;

        const next = list.step(openId, step);
        if (next === undefined) return;

        showEmail(next, "replace");

        // Stepping onto a row that is not on screen scrolls it into view; a highlighted row
        // nobody can see is the panel and the table saying different things.
        const row = list.rowOf(next);
        if (row !== undefined) virtualizer.virtualizer.scrollToIndex(row, {align: "auto"});
    }

    /** The table's one preview, driven by the rows below; see MailRowPreview. */
    let rowPreview: ReturnType<typeof MailRowPreview> | undefined = $state();

    /**
     * The bar above the rows, measured: what is in it decides how tall it is -- the filters wrap
     * on a narrow window, and the selection bar is another height again -- and the headers pinned
     * under it have to sit exactly on its edge. A gap of a few pixels is rows shimmering through
     * between the two.
     */
    let barElement = $state<HTMLDivElement | null>(null);
    let barHeight = $state(0);

    $effect(() => {
        const element = barElement;
        if (element === null) return;

        const observer = new ResizeObserver(() => (barHeight = element.getBoundingClientRect().height));
        observer.observe(element);
        return () => observer.disconnect();
    });

    /** Where the pinned headers rest, and the line the topmost row is measured against. */
    const pinnedTop = $derived(BAR_TOP + barHeight);

    /** The box the rows sit in, measured to know where the list starts on the page. */
    let listElement = $state<HTMLDivElement | null>(null);

    /**
     * How far down the page the first row sits. The page is the scroll container, so this is
     * what tells the virtualizer which of its rows the scroll position means -- and it is why
     * the greeting and the heatmap above simply scroll away before the rows start moving.
     */
    let scrollMargin = $state(0);

    $effect(() => {
        const element = listElement;
        if (element === null) return;

        const measure = () => (scrollMargin = element.getBoundingClientRect().top + window.scrollY);
        measure();

        // Everything above the list can change height -- the heatmap filling in, the greeting
        // arriving, the window being resized -- and each of those moves where the list starts.
        const observer = new ResizeObserver(measure);
        observer.observe(document.body);
        return () => observer.disconnect();
    });

    /** Whether the length is known at all yet. Before that a handful of rows stand in for it. */
    const visibleRowCount = $derived(
        list.layout.length === 0 && !list.initialized ? PLACEHOLDER_ROWS : list.layout.length
    );

    const virtualizer = createWindowVirtualizer<HTMLTableRowElement>(() => {
        // Read out here rather than inside the callbacks below: those are called by the
        // virtualizer, long after the effect that tracks these options has run.
        const layout = list.layout;

        return {
            count: visibleRowCount,
            // Follows the layout: a stretch of headers appearing changes which rows are tall.
            estimateSize: (index: number) => {
                const row = layout.rowAt(index);
                if (row?.kind !== "header") return ROW_HEIGHT;

                return row.node.level === 0 ? HEADER_HEIGHT : SUB_HEADER_HEIGHT;
            },
            overscan: OVERSCAN,
            scrollMargin,
        };
    });

    /**
     * The rows on screen: what the layout says each of them is, and the mail it holds once that
     * is known. A `row` of undefined is a mail whose page is not here yet.
     */
    const visible = $derived(
        virtualizer.items
            .filter((item) => item.index < visibleRowCount)
            .map((item) => {
                const entry = list.layout.rowAt(item.index);
                if (entry?.kind === "header") return {item, entry, row: undefined};

                const id = entry === undefined ? undefined : list.idAt(entry);
                const mail = id === undefined ? null : mails.peek(id).value;
                return {
                    item,
                    entry,
                    row: id !== undefined && mail !== null ? {id, mail} : undefined,
                };
            })
    );

    /**
     * The rows the table has data for. Only those: a mail the list has not fetched yet is a gap
     * the virtualizer keeps open, not a row with nothing in it.
     */
    const dataRows = $derived<MailTableRow[]>(
        visible.map(({row}) => row).filter((row): row is MailTableRow => row !== undefined)
    );

    const table = createTable({
        features,
        columns,
        get data() {
            return dataRows;
        },
        getRowId: (row) => row.id,
    });

    // Looked up by id rather than by position: what the table holds is the window, and an index
    // in it says nothing about the row's place in the mailbox.
    const rowsById = $derived(new Map(table.getRowModel().rows.map((row) => [row.id, row])));

    // Asking is a write, so it happens in an effect: pages that are missing are fetched and the
    // rows on screen are subscribed. Fires again as answers land until the range is covered.
    $effect(() => {
        const first = visible[0]?.item.index;
        const last = visible.at(-1)?.item.index;

        if (first === undefined || last === undefined) {
            list.window(0, 0);
            return;
        }   

        list.window(first, last);
    });

    $effect(() => () => list.dispose());

    // Spacer rows rather than absolutely positioned ones: a `<tr>` taken out of the flow loses
    // the table layout, and with it the column widths.
    //
    // Item positions are page positions, so the margin comes back off them -- what is padded
    // here is the space inside the list, not the space in front of it.
    const paddingTop = $derived((visible[0]?.item.start ?? scrollMargin) - scrollMargin);
    const paddingBottom = $derived(
        virtualizer.totalSize - ((visible.at(-1)?.item.end ?? scrollMargin) - scrollMargin)
    );

    /**
     * The headers that stay under the bar while their stretch is scrolled through, and how far
     * the next one has pushed them up.
     *
     * Copies rather than the rows themselves: a windowed table drops the header row from the DOM
     * long before the stretch is over, so there is nothing left up there to make sticky. What
     * decides is the row the line at [pinnedTop] falls in -- every header that row is under and
     * that has already gone past the line is drawn again here, outermost first.
     *
     * The push is what CSS does on its own between two sticky headers: as the next one comes up
     * from below it takes the stack's place rather than sliding under it.
     */
    const pinned = $derived.by(() => {
        const line = virtualizer.scrollOffset + pinnedTop;
        const crossing = visible.findIndex(({item}) => item.end > line);
        if (crossing < 0) return {headers: [] as MailGroupNode[], push: 0};

        // The air a header keeps above itself still belongs to the stretch before it: as long as
        // its own line has not reached the top, that stretch is what is being read.
        const found = visible[crossing];
        const early =
            found.entry?.kind === "header" &&
            found.item.start + headerTopMargin(found.entry.node.level) > line;
        const top = early ? (visible[crossing - 1] ?? found) : found;

        const layout = list.layout;
        const headers = layout
            .headersAt(top.item.index)
            .filter((node) => (layout.headerRow(node) ?? top.item.index) < top.item.index);
        if (headers.length === 0) return {headers, push: 0};

        const height = PINNED_PAD + headers.length * PINNED_HEIGHT;
        for (const {item, entry} of visible) {
            if (entry?.kind !== "header") continue;

            const drawn = item.start + headerTopMargin(entry.node.level);
            if (drawn < line) continue;

            return {headers, push: Math.max(0, height - (drawn - line))};
        }

        return {headers, push: 0};
    });
</script>

<section class="flex flex-col">
    <!-- Stays under the app header while the rows run past it: this is the bar the filters go
         into, and a filter that scrolls out of reach is one nobody uses. `top-12` is that
         header's height, and the background is its own -- rows would show through it.

         Its last few pixels are a gradient rather than an edge: a row passing underneath
         dissolves into the bar instead of being cut off by it, which is what says "header" and
         not "first row of the list". Nothing is tinted while the list sits at the top -- what is
         behind the fade there is the page itself. -->
    <!-- What is in it depends on what the reader is doing: which list this is, or -- while mails
         are picked -- what has been picked and what can be done to it. The same bar either way,
         and the same height, so the list below does not move as the two swap. The toggle and the
         buttons are both 2rem, which is what sets it. -->
    <div
            bind:this={barElement}
            class={cn(
                "sticky top-12 z-20 flex items-center px-4 py-2",
                // While a header is pinned under it the fade moves down there, or a row would
                // vanish behind that header and come back out through this gradient.
                pinned.headers.length > 0
                    ? "bg-background"
                    : "bg-linear-to-b from-background from-[calc(100%-0.75rem)] to-transparent"
            )}
    >
        {#if selection.active}
            <MailSelectionBar {selection}/>
        {:else}
            <div class="flex flex-col w-full gap-2">
                <div class="flex w-full items-baseline gap-2">
                    <!-- The view's own name, and a double click edits it: a listing somebody
                         put together is called what they call it. A listing that is nobody's says
                         what it holds instead, and there is nothing to rename about that. -->
                    {#if renaming && name !== null && onRename !== undefined}
                        <RenameInput
                                {name}
                                onEnd={endRename}
                                label={$_("views.nameLabel")}
                                class="font-heading min-w-0 flex-1 bg-transparent text-lg font-medium outline-hidden"
                        />
                    {:else}
                        <!-- svelte-ignore a11y_no_noninteractive_element_interactions -->
                        <h2
                                class="font-heading text-lg font-medium"
                                ondblclick={() => (renaming = onRename !== undefined && name !== null)}
                        >
                            {name ?? $_(allMails ? "mails.title.all" : "mails.title.unarchived")}
                        </h2>
                    {/if}
                    {#if list.initialized}
                    <span class="text-muted-foreground text-xs tabular-nums">
                        {$_("mails.count", {values: {count: list.total}})}
                    </span>
                    {/if}
                </div>

                <div class="flex flex-row flex-wrap items-center gap-2">
                    <!-- Bound to the filter through a getter and a setter each: the view is the
                         one copy of what is filtered, and a chip is a way of writing into it. -->
                    <LabelFilter
                            bind:ids={
                                () => view.filter.hasLabels ?? [],
                                (ids) => withFilter({hasLabels: idsOrNull(ids)})
                            }
                    />
                    <IsUnreadFilter
                            bind:selected={
                                () => readStates(view.filter.readState),
                                (states) => withFilter({readState: readStateOf(states)})
                            }
                    />
                    <IsArchiveFilter
                            bind:selected={
                                () => view.filter.archivedState ?? [],
                                (states) => withFilter({archivedState: states.length === 0 ? null : states})
                            }
                    />
                    <!-- The same control twice: who a mail came from, and who it went to. -->
                    <SenderFilter
                            title={$_("filters.from")}
                            icon={UserIcon}
                            bind:ids={
                                () => view.filter.sentBy ?? [],
                                (ids) => withFilter({sentBy: idsOrNull(ids)})
                            }
                    />
                    <SenderFilter
                            title={$_("filters.to")}
                            icon={UsersIcon}
                            bind:ids={
                                () => view.filter.sentTo ?? [],
                                (ids) => withFilter({sentTo: idsOrNull(ids)})
                            }
                    />
                    <InboxFilter
                            bind:ids={
                                () => view.filter.imapAccountIds ?? [],
                                (ids) => withFilter({imapAccountIds: idsOrNull(ids)})
                            }
                    />

                    <!-- What the caller offers about this listing -- keeping it, above all. -->
                    <div class="ml-auto flex flex-row items-center gap-2">
                        {@render actions?.()}

                        <!-- The other side of the row: what is shown is set on the left, how it
                             is arranged here. -->
                        <GroupingSettings bind:groupings={view.groupings} bind:sorting={view.sorting}/>
                    </div>
                </div>
            </div>
        {/if}
    </div>

    <!--
        The stretch being read, held under the bar.

        Outside the table and not a sticky row: the rows of a windowed table are dropped from the
        DOM as they leave the viewport, so the header of a long stretch is gone long before the
        stretch is. This has no height of its own -- it is drawn over the rows, not between them.
    -->
    <div class="sticky z-10 h-0" style="top: {pinnedTop}px">
        {#if pinned.headers.length > 0}
            <!-- One group for the cursor, like a row of the table: hovering the pinned header
                 brings its box out the same way hovering the real one does. -->
            <!-- The last stretch of it is a gradient rather than an edge: a row passing
                 underneath dissolves into the header instead of being cut off by it. Which is
                 what the bar above does while nothing is pinned. -->
            <div
                    class="group/mail-row bg-background absolute inset-x-0 top-0 pt-2.5
                           after:pointer-events-none after:absolute after:inset-x-0 after:top-full
                           after:h-3 after:bg-linear-to-b after:from-background after:to-transparent
                           after:content-['']"
                    style="transform: translateY(-{pinned.push}px)"
            >
                {#each pinned.headers as node (node.path.join("/"))}
                    <MailGroupHeader {node} {list} pinned/>
                {/each}
            </div>
        {/if}
    </div>

    <!-- No scroll container of its own: the page is the one, so the greeting and the heatmap
         above scroll away before the rows begin to move. The table's own wrapper is kept from
         scrolling as well, or it would clip the rows vertically along with sideways. -->
    <div
            bind:this={listElement}
            class="[&>[data-slot=table-container]]:overflow-visible"
    >
        <!-- The row count is stated for a screen reader, because the DOM no longer carries it: a
             windowed table holds the rows near the viewport and nothing else. -->
        <!-- The body reads back rather than at full contrast: a mailbox is a long list of rows nobody
             reads one by one, so what has been read stays quiet and the unread rows below step
             forward against it. -->
        <Table.Root class="text-muted-foreground min-w-[52rem] table-fixed" aria-rowcount={list.total}>
            <colgroup>
                {#each columns as column (column.id)}
                    {@const width = COLUMN_WIDTHS[column.id ?? ""]}
                    <col style={width ? `width: ${width}` : undefined}/>
                {/each}
            </colgroup>

            <Table.Body>
                <!-- The rows that are not rendered, as height. A bare row rather than a
                     Table.Row: it carries no border, no hover and nothing to read, it is the
                     space the list would have taken. A `<tr>` without a cell has no height. -->
                {#if paddingTop > 0}
                    <tr aria-hidden="true">
                        <td colspan={columns.length} class="p-0" style="height: {paddingTop}px"></td>
                    </tr>
                {/if}

                <!-- The kind is part of the key: a row that was a header and is now a gap -- which
                     is what a change of filter makes of it, until the new listing is here -- has
                     to be a new block rather than the old one updated. Updated, the header
                     component lives on for a beat with nothing behind it and reads a stretch that
                     is no longer there. -->
                {#each visible as {item, entry, row} (row?.id ?? `${entry?.kind ?? "gap"}-${item.index}`)}
                    {@const modelRow = row === undefined ? undefined : rowsById.get(row.id)}
                    {#if entry?.kind === "header"}
                        <!-- The stretch this and the rows below it belong to. One cell across the
                             table: a heading is not a value in a column. -->
                        <!-- A row of the table like any other as far as the cursor goes: the
                             header's own box comes out on hover, see selectionReveal. -->
                        <Table.Row class="group/mail-row border-0 hover:bg-transparent">
                            <Table.Cell colspan={columns.length} class="p-0 align-bottom">
                                <MailGroupHeader node={entry.node} {list}/>
                            </Table.Cell>
                        </Table.Row>
                    {:else if modelRow}
                        <!-- No line between rows: what separates them is the row height and the
                             hover, which is enough for one clipped line per mail. -->
                        <!-- mousemove rather than mouseenter: the preview follows the cursor
                             along the row, and the wait for it runs from the first move on. -->
                        <!-- A click opens the mail beside the list, a double click goes to its own
                             page -- here, not in a new tab: the click that came first has pushed
                             the panel into the history, so the page's back button leads to the
                             list with that mail still open. The query is what puts it there. -->
                        <!-- svelte-ignore a11y_click_events_have_key_events -->
                        <!-- The row is the group the avatar and its checkbox swap on, and a
                             mail that has been picked takes the one colour of this theme that is
                             not a grey -- so the picked rows stand out of a list whose every
                             other state, the hover and the open mail included, is a shade of
                             it. The text keeps the tones it has: what is picked is the row. -->
                        <Table.Row
                                aria-rowindex={item.index + 1}
                                data-state={modelRow.id === openId ? "selected" : undefined}
                                class={cn(
                                    "group/mail-row h-10 cursor-pointer border-0",
                                    selection.has(modelRow.id) &&
                                        "bg-accent hover:bg-accent data-[state=selected]:bg-accent"
                                )}
                                onmousemove={(event) => rowPreview?.hover(event, modelRow.id)}
                                onmouseleave={() => rowPreview?.leave()}
                                onclick={() => showEmail(modelRow.id, "push")}
                                ondblclick={() => openEmailPage(modelRow.id, row?.mail.subject)}
                        >
                            <!-- The indent goes on the first cell: the columns are fixed, so a
                                 row that steps in steps in where its avatar is. -->
                            {#each modelRow.getAllCells() as cell, column (cell.id)}
                                <Table.Cell
                                        class="h-10 overflow-hidden py-0"
                                        style={column === 0 && entry?.kind === "mail"
                                            ? mailIndent(entry.path.length)
                                            : undefined}
                                >
                                    <FlexRender {cell}/>
                                </Table.Cell>
                            {/each}
                        </Table.Row>
                    {:else}
                        <!-- A mail the list has and does not hold yet: the shape of the row
                             without the content, so nothing shifts when it arrives. -->
                        <Table.Row aria-rowindex={item.index + 1} class="h-10 border-0 hover:bg-transparent">
                            {#each columns as column, index (column.id)}
                                {@const ghost = GHOST_SHAPES[column.id ?? ""]}
                                <!-- The same indent as the mail that lands here, so nothing
                                     shifts sideways when it arrives. -->
                                <Table.Cell
                                        class="h-10 overflow-hidden py-0"
                                        style={index === 0 && entry?.kind === "mail"
                                            ? mailIndent(entry.path.length)
                                            : undefined}
                                >
                                    {#if ghost}
                                        <MailGhostCell widthClass={ghost.width} withAvatar={ghost.withAvatar}/>
                                    {/if}
                                </Table.Cell>
                            {/each}
                        </Table.Row>
                    {/if}
                {/each}

                {#if paddingBottom > 0}
                    <tr aria-hidden="true">
                        <td colspan={columns.length} class="p-0" style="height: {paddingBottom}px"></td>
                    </tr>
                {/if}

                {#if list.initialized && list.layout.length === 0}
                    <Table.Row class="border-0 hover:bg-transparent">
                        <Table.Cell colspan={columns.length} class="h-24 text-center">
                            <MailsEmpty/>
                        </Table.Cell>
                    </Table.Row>
                {/if}
            </Table.Body>
        </Table.Root>
    </div>

    <MailRowPreview bind:this={rowPreview}/>

    {#if openId !== null}
        <!-- Not keyed on the mail: a step hands the panel another id, it does not slide a second
             one in over the first. -->
        <MailDetailPanel
                id={openId}
                canStepUp={list.canStep(openId, -1)}
                canStepDown={list.canStep(openId, 1)}
                onStep={stepEmail}
                onClose={closeEmail}
        />
    {/if}

    {#if list.failed}
        <div class="flex items-center gap-3">
            <p class="text-destructive text-xs">{$_("mails.loadFailed")}</p>
            <Button variant="outline" size="sm" onclick={() => list.retry()}>
                {$_("mails.retry")}
            </Button>
        </div>
    {/if}
</section>
