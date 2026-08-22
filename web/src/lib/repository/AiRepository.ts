const API = '/api/ai';

/** What POST /reset threw away. */
export type AgentWorkReset = {
	/** Whether the processor was working through the mailbox when the request came in. */
	processor_was_running: boolean;
	/** Mails the agent had filed under a tag, which are now unfiled. */
	removed_tag_links: number;
	/** Tags the agent invented and nobody has used since. */
	removed_tags: number;
	removed_thread_links: number;
	removed_threads: number;
	/** Mails that carried a processing stamp and are due again. */
	unstamped_mails: number;
};

/** Operating the mail agent from the outside. */
export class AiRepository {
	/**
	 * Stops the processor and throws away everything the agent filed. Not scoped to the caller —
	 * this clears the agent's work across the whole installation — and the processor stays down
	 * until the server is restarted.
	 */
	async resetAgentWork(): Promise<AgentWorkReset> {
		const response = await fetch(`${API}/reset`, { method: 'POST', credentials: 'include' });
		if (!response.ok) throw new Error(`Could not reset the agent's work: ${response.status}`);
		return (await response.json()) as AgentWorkReset;
	}
}

export const aiRepository = new AiRepository();
