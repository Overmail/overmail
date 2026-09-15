import {
    forceCollide,
    forceLink,
    forceManyBody,
    forceSimulation,
    forceX,
    forceY,
    type Simulation,
    type SimulationLinkDatum,
    type SimulationNodeDatum,
} from 'd3-force';

export class LabelMapSimulation {
    labels: LabelMapLabel[] = [];
    nodes: LabelMapNode[] = [];
    links: LabelMapLink[] = [];
    simulation?: Simulation<LabelMapNode, LabelMapLink>;

    async fetchLabels() {
        const response = await fetch('/api/labels/map');

        if (!response.ok) {
            throw new Error('Failed to fetch labels');
        }

        this.labels = (await response.json()).labels;
    }

    calculate() {
        const labelById = new Map(
            this.labels.map(label => [label.id, label])
        );

        this.nodes = this.labels.map(label => ({
            id: label.id,
            label,
            radius: this.getRadius(label.email_count),
            x: Math.random() * 1000 - 500,
            y: Math.random() * 1000 - 500,
        }));

        this.links = [];

        for (const label of this.labels) {
            for (const relation of label.relations) {
                if (!labelById.has(relation.label_id)) {
                    continue;
                }

                /*
                 * Relations are undirected, so only create one
                 * link for A <-> B.
                 */
                if (label.id >= relation.label_id) {
                    continue;
                }

                this.links.push({
                    source: label.id,
                    target: relation.label_id,
                    count: relation.count,
                });
            }
        }

        this.simulation = forceSimulation<LabelMapNode>(this.nodes)
            .force(
                'charge',
                forceManyBody<LabelMapNode>()
                    .strength(node => -80 - node.radius * 8)
                    .distanceMax(this.nodes.length * 3.5) // This makes a somewhat acceptable layout to allow for more exclusive clusters whithout having to zoom out too much. The more nodes, the more distance is needed to avoid overlap.
            )
            .force(
                'link',
                forceLink<LabelMapNode, LabelMapLink>(this.links)
                    .id(node => node.id)
                    .distance(link => this.getLinkDistance(link))
                    .strength(link => this.getLinkStrength(link))
            )
            .force(
                'x',
                forceX<LabelMapNode>(0)
                    .strength(0.015)
            )
            .force(
                'y',
                forceY<LabelMapNode>(0)
                    .strength(0.015)
            )
            .force(
                'collide',
                forceCollide<LabelMapNode>()
                    .radius(node => node.radius + 8)
                    .strength(0.9)
                    .iterations(2)
            );

        return this.nodes;
    }

    private getRadius(emailCount: number): number {
        /*
         * Square-root scaling keeps large labels from dominating the map.
         */
        return 20 + (emailCount * 40 * emailCount / this.labels.length) ** 0.8;
    }

    private getLinkDistance(link: LabelMapLink): number {
        /*
         * A stronger relation means a shorter distance between the labels.
         */
        const count = Math.max(link.count, 1);

        return Math.max(
            50,
            220 - Math.sqrt(count) * 20
        );
    }

    private getLinkStrength(link: LabelMapLink): number {
        /*
         * Logarithmic scaling prevents very large relation counts
         * from completely dominating the simulation.
         */
        const count = Math.max(link.count, 1);

        return Math.min(
            1,
            0.15 + Math.log10(count + 1) * 0.2
        );
    }

    stop() {
        this.simulation?.stop();
    }

    restart() {
        this.simulation?.alpha(1).restart();
    }
}

export type LabelMapNode = SimulationNodeDatum & {
    id: string;
    label: LabelMapLabel;
    radius: number;
};

export type LabelMapLink = SimulationLinkDatum<LabelMapNode> & {
    source: string;
    target: string;
    count: number;
};

export type LabelMapLabel = {
    id: string;
    name: string;
    color: string;
    email_count: number;
    relations: {
        label_id: string;
        count: number;
    }[];
};