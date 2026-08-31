import type {Prompt, PromptLabel, PromptSegment, PromptSender} from "./prompt";

export type LabelSearchResult = PromptLabel & {emailCount: number};

export type SenderSearchResult = PromptSender & {emailCount: number};

// end ist exklusiv, passend für String.slice.
export type MatchableText = {
    text: string;
    matches: {start: number; end: number}[];
};

export type EmailSearchResult = {
    id: string;
    subject: MatchableText;
    from: {
        name: MatchableText | null;
        address: MatchableText;
    };
    avatarUrl: string | null;
    to: string[];
    date: string;
};

export class OvermailPromptViewModel {
    prompt: Prompt = $state({
        segments: [],
    });

    isEmpty = $derived(
        this.prompt.segments.every((s) => s.type === "text" && s.content.trim() === "")
    );

    currentModel: {type: "result", model: string} | {type: "loading"} = $state({type: "loading"});

    constructor() {
        fetch("/api/webapp/ai/current-config")
            .then((response) => response.json())
            .then((data) => {
                this.currentModel = {type: "result", model: data.model_id};
            });
    }

    setSegments(segments: PromptSegment[]) {
        this.prompt.segments = segments;
    }

    async findLabels(query: string): Promise<LabelSearchResult[]> {
        const response = await fetch(`/api/labels/search?query=${encodeURIComponent(query)}`);
        if (!response.ok) return [];

        const data: {
            labels: {id: string; name: string; color: string; email_count: number}[];
        } = await response.json();

        return data.labels.map((label) => ({
            id: label.id,
            name: label.name,
            color: label.color,
            emailCount: label.email_count,
        }));
    }

    async findSenders(query: string): Promise<SenderSearchResult[]> {
        const response = await fetch(`/api/senders/search?query=${encodeURIComponent(query)}`);
        if (!response.ok) return [];

        const data: {
            senders: {
                id: string;
                name: string | null;
                address: string;
                avatar_url: string | null;
                email_count: number;
            }[];
        } = await response.json();

        return data.senders.map((sender) => ({
            id: sender.id,
            name: sender.name,
            address: sender.address,
            avatarUrl: sender.avatar_url,
            emailCount: sender.email_count,
        }));
    }

    async findEmails(query: string): Promise<EmailSearchResult[]> {
        const response = await fetch(`/api/emails/search?query=${encodeURIComponent(query)}`);
        if (!response.ok) return [];

        const data: {
            emails: {
                id: string;
                subject: MatchableText;
                from: {name: MatchableText | null; address: MatchableText};
                avatar_url: string | null;
                to: string[];
                date: string;
            }[];
        } = await response.json();

        return data.emails.map((email) => ({
            id: email.id,
            subject: email.subject,
            from: email.from,
            avatarUrl: email.avatar_url,
            to: email.to,
            date: email.date,
        }));
    }
}
