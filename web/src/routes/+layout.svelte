<script lang="ts">
	import './layout.css';
	import favicon from '$lib/assets/favicon.svg';
	import {page} from '$app/state';
	import {goto, onNavigate} from '$app/navigation';
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
	import {morphsBetweenPanelAndPage, startMorph} from '$lib/app/mails/mailViewTransition';
	import SettingsDialog from "$lib/app/settings/SettingsDialog.svelte";

	let { children } = $props();

	/**
	 * The browser's view transitions, for the one navigation that has two views of the same mail at
	 * its ends: the panel beside the list and the mail's own page. See mailViewTransition, which
	 * says which navigation that is and which elements are the same thing at both ends.
	 *
	 * Everything else navigates the way it did. Returning nothing here is the plain swap, and it is
	 * also what a browser without the API and a reader who asked for less motion get.
	 */
	onNavigate((navigation) => {
		if (!morphsBetweenPanelAndPage(navigation.from?.url, navigation.to?.url)) return;
		if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) return;

		// The contract of onNavigate: SvelteKit waits for what this returns before it touches the
		// DOM. Resolving inside the update callback is what puts the navigation between the two
		// snapshots the browser takes -- the old one is already captured when it runs, the new one
		// is taken once the page it awaits is rendered.
		return new Promise((resolve) => {
			const transition = startMorph(async () => {
				resolve();
				await navigation.complete;
			});

			// No view transitions here: the plain swap, right away.
			if (transition === null) resolve();
		});
	});

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

		<!-- Three variables, for everything that has to live with the panel: its width, how much
		     of the window's inline end it takes right now (nothing while it is closed), and how
		     long a change of the two may take -- nothing at all while it is being dragged, or
		     whatever follows the width would trail a fifth of a second behind the pointer. -->
		<Sidebar.Provider
				style="--panel-width: {panel.width}px;
				       --panel-offset: {panel.open ? panel.width : 0}px;
				       --panel-duration: {panel.isResizing ? 0 : 200}ms;"
		>
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
			<!-- The box in the flow is the offset, so the two cannot drift apart. -->
			<div class="w-(--panel-offset) shrink-0 transition-[width] duration-(--panel-duration) ease-linear"></div>
			<div
					bind:this={panelElement}
					class={[
						// Over everything the page puts up, the mail panel included: while this one
						// slides in, the page moves aside under it, and a panel out there that
						// paints over it would show through the edge. Its own z is also the
						// stacking context the grip lives in, which has to reach over the header.
						"fixed inset-y-0 z-50 flex w-(--panel-width) flex-col border-s bg-background",
						"transition-[left,right] duration-(--panel-duration) ease-linear",
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

<SettingsDialog />