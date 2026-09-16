export type EmailBody = {
    text: string | null,
    html: string | null,
};

/** How many bodies are kept; the oldest is dropped past that. A body never changes once sent. */
const CACHE_SIZE = 50;

export class EmailBodyRepository {
    private readonly loaded = new Map<string, EmailBody>();
    private readonly pending = new Map<string, Promise<EmailBody>>();

    /**
     * The body if it has been loaded before, synchronously. What lets a view that is mounted
     * anew for a mail already shown -- the panel turning into the page -- render it on the first
     * paint instead of a skeleton.
     */
    peek(emailId: string): EmailBody | null {
        return this.loaded.get(emailId) ?? null;
    }

    getBody(emailId: string): Promise<EmailBody> {
        const cached = this.loaded.get(emailId);
        if (cached) return Promise.resolve(cached);

        const inFlight = this.pending.get(emailId);
        if (inFlight) return inFlight;

        const request = this.fetchBody(emailId)
            .then((body) => {
                this.loaded.set(emailId, body);
                if (this.loaded.size > CACHE_SIZE) {
                    this.loaded.delete(this.loaded.keys().next().value!);
                }
                return body;
            })
            .finally(() => this.pending.delete(emailId));

        this.pending.set(emailId, request);
        return request;
    }

    private async fetchBody(emailId: string): Promise<EmailBody> {
        const response = await fetch(`/api/emails/${emailId}/body`);
        if (!response.ok) {
            throw new Error(`Failed to fetch email body for emailId: ${emailId}`);
        }
        const data = await response.json();
        return {
            text: data.text,
            html: data.html,
        };
    }
}
