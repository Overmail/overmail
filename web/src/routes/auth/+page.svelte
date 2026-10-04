<script lang="ts">
	import { onMount } from 'svelte';
	import { useAuthentiktContext } from '@julius-babies/authentikt-svelte';
	import { _ } from 'svelte-i18n';
	import { useRepositories } from '$lib/repository/repositories';
	import EmailStep from '$lib/app/auth/EmailStep.svelte';
	import EmailVerificationStep from '$lib/app/auth/EmailVerificationStep.svelte';
	import DoneStep from '$lib/app/auth/DoneStep.svelte';
	import { initStepTransitions } from '$lib/app/auth/stepTransition';

	const auth = useAuthentiktContext();
	const repositories = useRepositories();
	initStepTransitions();

	/**
	 * Our login endpoint is `POST /api/auth/login`, not the `GET /api/login` that
	 * `auth.startLoginFlow()` calls, so the session is created here and handed to the client.
	 */
	async function start() {
		await auth.linkToFlow(await repositories.auth.startLogin());
	}

	/** The tab names the step, so a reader coming back to it knows where the sign-in stands. */
	const titleKey = $derived.by(() => {
		const step = auth.currentFlow?.step;
		if (step?.type !== 'step') return 'auth.signin.title';
		switch (step.namespace) {
			case 'overmail/email-verification':
				return 'auth.signin.code.pageTitle';
			case 'authentikt-builtin/done':
				return 'auth.signin.done.pageTitle';
			default:
				return 'auth.signin.title';
		}
	});

	// A flow in the URL (a reload mid sign-in) is resumed by the client on its own.
	onMount(() => {
		if (!auth.currentFlow) void start();
	});
</script>

<svelte:head>
	<title>{$_(titleKey)} - {$_('app.name')}</title>
</svelte:head>

<!-- A grid of one cell the height of the column: the steps share it, see AuthStep. Clipped, so
     a step on its way in or out never shows a scrollbar. -->
<div class="grid h-full overflow-hidden">
	{#if auth.currentFlow}
		<!-- Keyed by the flow, so starting over gives every step a fresh plugin and empty fields. -->
		{#key auth.currentFlow.session_id}
			<EmailStep />
			<EmailVerificationStep onRestart={start} />
			<DoneStep />
		{/key}
	{/if}
</div>
