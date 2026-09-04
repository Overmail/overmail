<script lang="ts">
	import './layout.css';
	import favicon from '$lib/assets/favicon.svg';
	import {page} from '$app/state';
	import {goto} from '$app/navigation';
	import {type Session} from '$lib/repository/AuthRepository';
	import {provideRepositories} from '$lib/repository/repositories';
	import * as Sidebar from "$lib/components/ui/sidebar";
	import AppNavbar from "$lib/app/shell/AppNavbar.svelte";
	import AppSidebar from "$lib/app/shell/sidebar/AppSidebar.svelte";
	import {createPageHeader} from '$lib/app/shell/pageHeader.svelte';
	import AppHeader from "$lib/app/shell/AppHeader.svelte";
	import CheckingSession from "$lib/app/shell/CheckingSession.svelte";
	import {SidePanelState} from "$lib/app/shell/sidePanel.svelte";
	import SidePanelResizer from "$lib/app/shell/SidePanelResizer.svelte";
	import {fade} from 'svelte/transition';

	let { children } = $props();

	// Set up here rather than in the header: the header is a sibling of the page, and only a
	// context from above the page reaches it.
	const header = createPageHeader();

	// Once, above every page: from here down `useRepositories()` resolves to this set.
	const repositories = provideRepositories();

	let session = $state<Session | null>(null);
	let checked = $state(false);

	// /auth is where you land when you are not signed in, so checking there would loop.
	const isAuthRoute = $derived(page.url.pathname.startsWith('/auth'));

	$effect(() => {
		if (isAuthRoute) {
			checked = true;
			return;
		}

		checked = false;
		repositories.auth.getSession().then((result) => {
			session = result;
			checked = true;
			if (!result) goto('/auth');
		});
	});

	// Whether it is open and how wide it is come out of the tab's sessionStorage, which the panel
	// may read because it is never part of a server render: it only exists once the session check
	// below is through, and that runs on the client.
	const panel = new SidePanelState();

	let panelElement: HTMLElement | undefined = $state();
</script>

<svelte:head><link rel="icon" href={favicon} /></svelte:head>

{#if !checked}
	<!-- `fixed inset-0` rather than a viewport height: on a phone `h-screen` is 100vh, which is
	     the viewport as it stands with the address bar out of the way and so taller than what is
	     on screen -- the logo would sit below the optical middle and the loader under the bar. -->
	<div transition:fade={{duration: 200}} class="fixed inset-0 z-20 flex flex-col items-center justify-center gap-4 bg-background">
		<CheckingSession />
	</div>
{:else if isAuthRoute || session}
	{#if session}

		<Sidebar.Provider style="--panel-width: {panel.width}px;">
			<AppNavbar />
			<!-- Renders the <main> and stretches to the wrapper's min-h-svh, so pages can size
			     their content with flex-1 instead of a percentage height that has nothing to
			     resolve against. -->
			<Sidebar.Inset>
				<AppHeader
						{header}
						bind:sidebarOpen={panel.open}
				/>
				{@render children()}
			</Sidebar.Inset>

			<!-- Same two-piece trick as the sidebar on the left, and the same timing: what sits in
			     the flow is a box that only animates its width, so the page is pushed aside over
			     those 200ms instead of jumping to the new width in one frame. The panel itself is
			     fixed and slides in over the box, which also keeps it in place when the page
			     behind it scrolls. -->
			<div
					class={[
						"shrink-0",
						// The panel follows the pointer during a drag; animating the box next to it
						// would leave the page a fifth of a second behind the edge being dragged.
						panel.isResizing ? "" : "transition-[width] duration-200 ease-linear",
						panel.open ? "w-(--panel-width)" : "w-0",
					]}
			></div>
			<div
					bind:this={panelElement}
					class={[
						// Above the header's z-30 rather than below it: the panel's own z is the
						// stacking context the grip lives in, and it reaches a few pixels into the
						// page, where the header would otherwise paint over its top.
						"fixed inset-y-0 z-40 flex w-(--panel-width) flex-col border-s bg-background",
						panel.isResizing ? "" : "transition-[left,right] duration-200 ease-linear",
						panel.open ? "inset-e-0" : "-inset-e-(--panel-width)",
					]}
			>
				<!-- Only while open: closed, the panel sits outside the window, and a handle out
				     there is still in the tab order. -->
				{#if panel.open}
					<SidePanelResizer {panel} element={panelElement} />
				{/if}
				<AppSidebar />
			</div>
		</Sidebar.Provider>
	{:else}
		{@render children()}
	{/if}
{/if}
