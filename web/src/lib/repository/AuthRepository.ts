const API = '/api/auth';

/** Namespaces the backend reports from GET /flow/{id}/check. */
export const STEP_IDENTIFIER = 'authentikt-builtin/email';
export const STEP_EMAIL_VERIFICATION = 'overmail/email-verification';
export const STEP_DONE = 'authentikt-builtin/done';

/**
 * Query parameters that carry a flow across a page load. The names are authentikt's own: it
 * appends these when it redirects a browser into the login UI itself (OAuth authorize, device
 * flow verification link), so reading them here means those hand-offs land in the running flow
 * instead of starting a fresh one.
 */
export const FLOW_ACTIVE_PARAM = '_authentikt_flow_active';
export const FLOW_ID_PARAM = '_authentikt_session_id';

export type Session = {
	user_id: string;
	username: string;
	email: string;
};

export type FlowStep = {
	type: string;
	namespace: string;
	payload: Record<string, unknown>;
};

/**
 * Everything the frontend knows about signing in. Authentikt drives the flow from the server:
 * we only ever ask which step is current, post an answer, and ask again.
 */
export class AuthRepository {
	/** The signed-in user, or null. Null is the normal answer, not an error. */
	async getSession(): Promise<Session | null> {
		const response = await fetch(`${API}/session`, { credentials: 'include' });
		if (!response.ok) return null;
		return (await response.json()) as Session;
	}

	/** Opens a sign-in flow and returns its id. */
	async startLogin(): Promise<string> {
		const response = await fetch(`${API}/login`, { method: 'POST', credentials: 'include' });
		if (!response.ok) throw new Error(`Could not start the login flow: ${response.status}`);
		const body = await response.json();
		return body.session_id as string;
	}

	/** Which step the flow is waiting on right now. */
	async checkFlow(sessionId: string): Promise<FlowStep> {
		const step = await this.readFlow(sessionId);
		if (!step) throw new Error(`Could not read the login flow ${sessionId}`);
		return step;
	}

	/**
	 * Same request, but a flow the server does not know is an answer rather than a failure.
	 *
	 * Flow sessions live in the server's memory only, so an id that came in over the URL can be
	 * stale -- a restart, a bookmark, a link someone kept -- and the check route does not answer
	 * politely for one it cannot find. Null means "start a new flow".
	 */
	async resumeFlow(sessionId: string): Promise<FlowStep | null> {
		return await this.readFlow(sessionId);
	}

	private async readFlow(sessionId: string): Promise<FlowStep | null> {
		const response = await fetch(`${API}/authentikt/flow/${sessionId}/check`, {
			credentials: 'include'
		});
		if (!response.ok) return null;
		return (await response.json()) as FlowStep;
	}

	/** Step 1. The backend accepts a username or an email here. */
	async submitIdentifier(sessionId: string, identifier: string): Promise<{ type: string }> {
		return await this.postStep(sessionId, STEP_IDENTIFIER, { email: identifier });
	}

	/** Step 2, the code we mailed out. */
	async submitCode(sessionId: string, code: string): Promise<{ type: string }> {
		return await this.postStep(sessionId, `${STEP_EMAIL_VERIFICATION}/verify`, { code });
	}

	/** Final step: this is the request that hands back the session cookie. */
	async finish(sessionId: string): Promise<{ type: string }> {
		const response = await fetch(
			`${API}/authentikt/flow/${sessionId}/steps/plugins/${STEP_DONE}`,
			{ credentials: 'include' }
		);
		if (!response.ok) throw new Error(`Could not finish the login flow: ${response.status}`);
		return await response.json();
	}

	async logout(): Promise<void> {
		await fetch(`${API}/logout`, { method: 'POST', credentials: 'include' });
	}

	private async postStep(
		sessionId: string,
		path: string,
		body: unknown
	): Promise<{ type: string }> {
		const response = await fetch(`${API}/authentikt/flow/${sessionId}/steps/plugins/${path}`, {
			method: 'POST',
			credentials: 'include',
			headers: { 'Content-Type': 'application/json' },
			body: JSON.stringify(body)
		});
		if (!response.ok) throw new Error(`Login step failed: ${response.status}`);
		return await response.json();
	}
}

export const authRepository = new AuthRepository();
