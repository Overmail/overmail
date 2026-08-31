import type {Prompt, PromptLabel, PromptSegment} from "./prompt";

export type LabelSearchResult = PromptLabel & {emailCount: number};

export class OvermailPromptViewModel {
    prompt: Prompt = $state({
        segments: [
            {type: "text", content: "Fasse "},
            {type: "email", emailId: "email-123"},
            {type: "text", content: " zusammen und versehe sie mit dem Label "},
            {type: "label", label: {id: "label-uni", name: "Uni", color: "#4f46e5"}},
            {type: "text", content: "."},
        ],
    });

    isEmpty = $derived(
        this.prompt.segments.every((s) => s.type === "text" && s.content.trim() === "")
    );

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
}
