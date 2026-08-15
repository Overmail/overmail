<script lang="ts">
	import { goto } from '$app/navigation';
	import {
		authRepository,
		STEP_DONE,
		STEP_EMAIL_VERIFICATION,
		STEP_IDENTIFIER
	} from '$lib/repository/AuthRepository';

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
				error = 'No account for that username or email.';
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
				error = 'That code is not right.';
				return;
			}
			await refresh();
		} finally {
			busy = false;
		}
	}
</script>

<h1>Sign in</h1>

{#if error}
	<p style="color: red">{error}</p>
{/if}

{#if namespace === STEP_IDENTIFIER}
	<form onsubmit={(event) => (event.preventDefault(), submitIdentifier())}>
		<label>
			Username or email
			<input bind:value={identifier} autocomplete="username" />
		</label>
		<button type="submit" disabled={busy || !identifier}>Continue</button>
	</form>
{:else if namespace === STEP_EMAIL_VERIFICATION}
	<form onsubmit={(event) => (event.preventDefault(), submitCode())}>
		<p>We sent a code to {maskedEmail}.</p>
		<label>
			Code
			<input bind:value={code} inputmode="numeric" autocomplete="one-time-code" />
		</label>
		<button type="submit" disabled={busy || !code}>Sign in</button>
		<button type="button" onclick={start} disabled={busy}>Start over</button>
	</form>
{:else}
	<p>Loading…</p>
{/if}
