export class EmailBodyRepository {
    async getBody(emailId: string): Promise<{
        text: string | null,
        html: string | null,
    }> {
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