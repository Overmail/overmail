<script lang="ts">
	import './layout.css';
	import favicon from '$lib/assets/favicon.svg';
	import {page} from '$app/state';
	import {goto} from '$app/navigation';
	import {authRepository, type Session} from '$lib/repository/AuthRepository';
	import * as Sidebar from "$lib/components/ui/sidebar";
	import AppNavbar from "$lib/app/shell/AppNavbar.svelte";
	import AppSidebar from "$lib/app/shell/sidebar/AppSidebar.svelte";
	import {createPageHeader} from '$lib/app/shell/pageHeader.svelte';
	import {_} from 'svelte-i18n';
	import AppHeader from "$lib/app/shell/AppHeader.svelte";

	let { children } = $props();

	// Set up here rather than in the header: the header is a sibling of the page, and only a
	// context from above the page reaches it.
	const header = createPageHeader();

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
		authRepository.getSession().then((result) => {
			session = result;
			checked = true;
			if (!result) goto('/auth');
		});
	});

	let sidebarOpen = $state(false);
</script>

<svelte:head><link rel="icon" href={favicon} /></svelte:head>

{#if !checked}
	<p>{$_('app.checkingSession')}</p>
{:else if isAuthRoute || session}
	{#if session}

		<Sidebar.Provider style="--panel-width: 36rem;">
			<AppNavbar />
			<!-- Renders the <main> and stretches to the wrapper's min-h-svh, so pages can size
			     their content with flex-1 instead of a percentage height that has nothing to
			     resolve against. -->
			<Sidebar.Inset>
				<AppHeader
						{header}
						bind:sidebarOpen={sidebarOpen}
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
						"shrink-0 transition-[width] duration-200 ease-linear",
						sidebarOpen ? "w-(--panel-width)" : "w-0",
					]}
			></div>
			<div
					class={[
						"fixed inset-y-0 z-10 flex w-(--panel-width) flex-col border-s bg-background",
						"transition-[left,right] duration-200 ease-linear",
						sidebarOpen ? "inset-e-0" : "-inset-e-(--panel-width)",
					]}
			>
				<AppSidebar />
			</div>
		</Sidebar.Provider>
	{:else}
		{@render children()}
	{/if}
{/if}
