export class DevicesSettingsRepository {
    async fetchAuthCode(): Promise<AuthCodeState> {
        try {
            const response = await fetch('/api/webapp/devices/auth/generate-auth-code');
            if (!response.ok) {
                throw new Error(`Failed to fetch auth code: ${response.statusText}`);
            }
            const data = await response.json();
            return {
                type: "ready",
                url: "overmail://" + encodeURIComponent(window.location.origin) + "/auth?code=" + data.code,
                validUntil: new Date(data.valid_until * 1000),
            };
        } catch (error) {
            console.error('Error fetching auth code:', error);
            return { type: "error" };
        }
    }
}

export type AuthCodeState = { type: "loading" } |
{ type: "ready", url: string, validUntil: Date } |
{ type: "error" }