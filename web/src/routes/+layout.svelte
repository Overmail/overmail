<script lang="ts">
	import './layout.css';
	import favicon from '$lib/assets/favicon.svg';
	import { page } from '$app/state';
	import { goto } from '$app/navigation';
	import { authRepository, type Session } from '$lib/repository/AuthRepository';

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
	<p>Checking session…</p>
{:else if isAuthRoute || session}
	{#if session}
		<p>
			Signed in as {session.username} ({session.email})
			<button
				onclick={async () => {
					await authRepository.logout();
					goto('/auth');
				}}>Log out</button
			>
		</p>
	{/if}
	{@render children()}
{/if}
