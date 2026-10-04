const API = '/api/auth';

export type Session = {
	user_id: string;
	username: string;
	email: string;
};

/**
 * The session around the sign-in. The flow itself is authentikt-svelte's, see /auth.
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

	async logout(): Promise<void> {
		await fetch(`${API}/logout`, { method: 'POST', credentials: 'include' });
	}
}
