const API = '/api/auth';

/** Namespaces the backend reports from GET /flow/{id}/check. */
export const STEP_IDENTIFIER = 'authentikt-builtin/email';
export const STEP_EMAIL_VERIFICATION = 'overmail/email-verification';
export const STEP_DONE = 'authentikt-builtin/done';

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
		const response = await fetch(`${API}/authentikt/flow/${sessionId}/check`, {
			credentials: 'include'
		});
		if (!response.ok) throw new Error(`Could not read the login flow: ${response.status}`);
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
