const API = '/api/webapp/home';

/** What GET /email-graph answers with. */
export type EmailGraph = {
	/** The year the counts below are from. */
	year: number;
	/** Every year there is mail in, oldest first. [year] need not be one of them. */
	available_years: number[];
	/**
	 * `yyyy-mm-dd` to how many mails arrived that day. A day nothing arrived on is absent rather
	 * than held at zero, which is exactly what UsageGraph expects.
	 */
	days: Record<string, number>;
};

/** How busy the mailbox was, day by day — what the home screen's heatmap is drawn from. */
export class EmailGraphRepository {
	/**
	 * The counts of one year, the current one if no [year] is given. Days are UTC days, as the
	 * server counts them.
	 */
	async getEmailGraph(year?: number): Promise<EmailGraph> {
		const query = year === undefined ? '' : `?year=${year}`;
		const response = await fetch(`${API}/email-graph${query}`, { credentials: 'include' });
		if (!response.ok) throw new Error(`Could not read the email graph: ${response.status}`);
		return (await response.json()) as EmailGraph;
	}
}

export const emailGraphRepository = new EmailGraphRepository();
