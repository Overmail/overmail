<script lang="ts">
	import { goto } from '$app/navigation';
	import {
		authRepository,
		STEP_DONE,
		STEP_EMAIL_VERIFICATION,
		STEP_IDENTIFIER
	} from '$lib/repository/AuthRepository';
	import { _ } from 'svelte-i18n';

	let sessionId = $state<string | null>(null);
	let namespace = $state<string | null>(null);
	let maskedEmail = $state<string | null>(null);

	let identifier = $state('');
	let code = $state('');
	let error = $state<string | null>(null);
	let busy = $state(false);

	$effect(() => {
		start();
	});

	async function start() {
		error = null;
		identifier = '';
		code = '';
		sessionId = await authRepository.startLogin();
		await refresh();
	}

	/**
	 * The server decides what comes next, so after every answer we just ask again instead of
	 * tracking the step order in here.
	 */
	async function refresh() {
		const step = await authRepository.checkFlow(sessionId!);
		namespace = step.namespace;
		maskedEmail = (step.payload?.email as string) ?? null;

		if (namespace === STEP_DONE) {
			await authRepository.finish(sessionId!);
			await goto('/');
		}
	}

	async function submitIdentifier() {
		busy = true;
		error = null;
		try {
			const result = await authRepository.submitIdentifier(sessionId!, identifier);
			if (result.type !== 'success') {
				error = $_('auth.signin.identifier.error');
				return;
			}
			await refresh();
		} finally {
			busy = false;
		}
	}

	async function submitCode() {
		busy = true;
		error = null;
		try {
			const result = await authRepository.submitCode(sessionId!, code);
			if (result.type !== 'success') {
				error = $_('auth.signin.code.error');
				return;
			}
			await refresh();
		} finally {
			busy = false;
		}
	}
</script>

<h1>{$_('auth.signin.title')}</h1>

{#if error}
	<p style="color: red">{error}</p>
{/if}

{#if namespace === STEP_IDENTIFIER}
	<form onsubmit={(event) => (event.preventDefault(), submitIdentifier())}>
		<label>
			{$_('auth.signin.identifier.label')}
			<input bind:value={identifier} autocomplete="username" />
		</label>
		<button type="submit" disabled={busy || !identifier}>
			{$_('auth.signin.identifier.submit')}
		</button>
	</form>
{:else if namespace === STEP_EMAIL_VERIFICATION}
	<form onsubmit={(event) => (event.preventDefault(), submitCode())}>
		<p>{$_('auth.signin.code.sent', { values: { email: maskedEmail } })}</p>
		<label>
			{$_('auth.signin.code.label')}
			<input bind:value={code} inputmode="numeric" autocomplete="one-time-code" />
		</label>
		<button type="submit" disabled={busy || !code}>{$_('auth.signin.code.submit')}</button>
		<button type="button" onclick={start} disabled={busy}>
			{$_('auth.signin.code.restart')}
		</button>
	</form>
{:else}
	<p>{$_('auth.signin.loading')}</p>
{/if}
