<script lang="ts">
	import './layout.css';
	import favicon from '$lib/assets/favicon.svg';
	import { page } from '$app/state';
	import { goto } from '$app/navigation';
	import { authRepository, type Session } from '$lib/repository/AuthRepository';
	import * as Sidebar from '$lib/components/ui/sidebar';
	import AppSidebar from "$lib/app/shell/AppSidebar.svelte";
	import CheckingSession from "$lib/app/shell/CheckingSession.svelte";
    import { fade } from 'svelte/transition';

	let { children } = $props();

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
	<div transition:fade={{duration: 200}} class="absolute top-0 left-0 z-20 flex flex-col items-center justify-center gap-4 w-screen h-screen">
		<CheckingSession />
	</div>
{:else if isAuthRoute || session}
	{#if session}

		<Sidebar.Provider>
			<AppSidebar />
			<!-- Renders the <main> and stretches to the wrapper's min-h-svh, so pages can size
			     their content with flex-1 instead of a percentage height that has nothing to
			     resolve against. -->
			<Sidebar.Inset>
				{@render children()}
			</Sidebar.Inset>
		</Sidebar.Provider>
	{:else}
		{@render children()}
	{/if}
{/if}
