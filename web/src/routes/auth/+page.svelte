<script lang="ts">
	import { goto, replaceState } from '$app/navigation';
	import { page } from '$app/state';
	import { onMount } from 'svelte';
	import {
		authRepository,
		FLOW_ACTIVE_PARAM,
		FLOW_ID_PARAM,
		STEP_DONE,
		STEP_EMAIL_VERIFICATION,
		STEP_IDENTIFIER,
		type FlowStep
	} from '$lib/repository/AuthRepository';

	let sessionId = $state<string | null>(null);
	let namespace = $state<string | null>(null);
	let maskedEmail = $state<string | null>(null);

	let identifier = $state('');
	let code = $state('');
	let error = $state<string | null>(null);
	let busy = $state(false);

	onMount(() => {
		resume();
	});

	/**
	 * The flow id sits in the URL, so reloading while waiting for the code puts the user back on
	 * the step they were on instead of starting over and mailing a second code. An id we cannot
	 * pick up -- stale, or the server restarted -- just means a fresh flow.
	 */
	async function resume() {
		const carried = page.url.searchParams.get(FLOW_ID_PARAM);
		const step = carried ? await authRepository.resumeFlow(carried) : null;

		// A finished flow hands out its cookie once, so resuming into one could only bounce off
		// the layout's session check. Start over instead.
		if (!step || step.namespace === STEP_DONE) {
			await start();
			return;
		}

		sessionId = carried;
		await show(step);
	}

	async function start() {
		error = null;
		identifier = '';
		code = '';
		sessionId = await authRepository.startLogin();
		carryInUrl(sessionId);
		await refresh();
	}

	/**
	 * Written with replaceState rather than a navigation: back should leave /auth, not walk
	 * through the ids of flows that are already spent. The parameter names are authentikt's own,
	 * so a link the server hands out itself (OAuth, device flow) arrives the same way.
	 */
	function carryInUrl(id: string) {
		const url = new URL(page.url);
		url.searchParams.set(FLOW_ACTIVE_PARAM, 'true');
		url.searchParams.set(FLOW_ID_PARAM, id);
		replaceState(url, page.state);
	}

	/**
	 * The server decides what comes next, so after every answer we just ask again instead of
	 * tracking the step order in here.
	 */
	async function refresh() {
		await show(await authRepository.checkFlow(sessionId!));
	}

	async function show(step: FlowStep) {
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
