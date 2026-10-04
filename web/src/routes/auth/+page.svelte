<script lang="ts">
	import { onMount } from 'svelte';
	import { useAuthentiktContext } from '@julius-babies/authentikt-svelte';
	import { _ } from 'svelte-i18n';
	import { useRepositories } from '$lib/repository/repositories';
	import EmailStep from '$lib/app/auth/EmailStep.svelte';
	import EmailVerificationStep from '$lib/app/auth/EmailVerificationStep.svelte';
	import DoneStep from '$lib/app/auth/DoneStep.svelte';

	const auth = useAuthentiktContext();
	const repositories = useRepositories();

	/**
	 * Our login endpoint is `POST /api/auth/login`, not the `GET /api/login` that
	 * `auth.startLoginFlow()` calls, so the session is created here and handed to the client.
	 */
	async function start() {
		await auth.linkToFlow(await repositories.auth.startLogin());
	}

	// A flow in the URL (a reload mid sign-in) is resumed by the client on its own.
	onMount(() => {
		if (!auth.currentFlow) void start();
	});
</script>

<div class="flex flex-col p-16 pt-32 overflow-y-auto">
	{#if auth.currentFlow}
		<!-- Keyed by the flow, so starting over gives every step a fresh plugin and empty fields. -->
		{#key auth.currentFlow.session_id}
			<EmailStep />
			<EmailVerificationStep onRestart={start} />
			<DoneStep />
		{/key}
	{/if}
</div>
