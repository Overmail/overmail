<script lang="ts">
	import './layout.css';
	import favicon from '$lib/assets/favicon.svg';
	import { page } from '$app/state';
	import { goto } from '$app/navigation';
	import { authRepository, type Session } from '$lib/repository/AuthRepository';
	import * as Sidebar from '$lib/components/ui/sidebar';
	import AppSidebar from "$lib/app/shell/AppSidebar.svelte";
	import AppHeader from "$lib/app/shell/AppHeader.svelte";
	import { createPageHeader } from '$lib/app/shell/pageHeader.svelte';
	import { _ } from 'svelte-i18n';

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
</script>

<svelte:head><link rel="icon" href={favicon} /></svelte:head>

{#if !checked}
	<p>{$_('app.checkingSession')}</p>
{:else if isAuthRoute || session}
	{#if session}

		<Sidebar.Provider>
			<AppSidebar />
			<!-- Renders the <main> and stretches to the wrapper's min-h-svh, so pages can size
			     their content with flex-1 instead of a percentage height that has nothing to
			     resolve against. -->
			<Sidebar.Inset>
				<AppHeader {header} />
				{@render children()}
			</Sidebar.Inset>
		</Sidebar.Provider>
	{:else}
		{@render children()}
	{/if}
{/if}
