import {
    forceCollide,
    forceLink,
    forceManyBody,
    forceSimulation,
    forceX,
    forceY,
    type ForceLink,
    type Simulation,
    type SimulationLinkDatum,
    type SimulationNodeDatum,
} from 'd3-force';

export class LabelMapSimulation {
    labels: LabelMapLabel[] = [];
    nodes: LabelMapNode[] = [];
    links: LabelMapLink[] = [];
    /** The nodes being simulated right now, see [show]; [nodes] holds every label. */
    visibleNodes: LabelMapNode[] = [];
    simulation?: Simulation<LabelMapNode, LabelMapLink>;
    private linkForce?: ForceLink<LabelMapNode, LabelMapLink>;

    async fetchLabels() {
        const response = await fetch('/api/labels/map');

        if (!response.ok) {
            throw new Error('Failed to fetch labels');
        }

        this.labels = (await response.json()).labels;
    }

    /** Sets up the simulation with every label, and shows those in [visible]. */
    calculate(visible: ReadonlySet<string>) {
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

        this.linkForce = forceLink<LabelMapNode, LabelMapLink>([])
            .id(node => node.id)
            .distance(link => this.getLinkDistance(link))
            .strength(link => this.getLinkStrength(link));

        this.simulation = forceSimulation<LabelMapNode>([])
            .force(
                'charge',
                forceManyBody<LabelMapNode>()
                    .strength(node => -80 - node.radius * 8)
                    .distanceMax(this.nodes.length * 3.5) // This makes a somewhat acceptable layout to allow for more exclusive clusters whithout having to zoom out too much. The more nodes, the more distance is needed to avoid overlap.
            )
            .force('link', this.linkForce)
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

        this.show(visible, new Map());

        return this.visibleNodes;
    }

    /**
     * Simulates only the labels in [simulated] and the links between them; every other label
     * stays where it is and pushes nothing. Those in [pinned] are simulated without moving: the
     * others are pushed and pulled by them, they are not. The labels in [appearing] are coming
     * onto the map and start inside the label they map to, to be pushed out of it.
     */
    show(
        simulated: ReadonlySet<string>,
        appearing: ReadonlyMap<string, LabelMapNode>,
        pinned: ReadonlySet<string> = new Set(),
    ) {
        if (!this.simulation || !this.linkForce) return;

        const isFirst = this.visibleNodes.length === 0;
        this.visibleNodes = this.nodes.filter(node => simulated.has(node.id));

        for (const node of this.visibleNodes) {
            node.fx = pinned.has(node.id) ? node.x : null;
            node.fy = pinned.has(node.id) ? node.y : null;

            const origin = appearing.get(node.id);
            if (!origin) continue;

            const angle = Math.random() * Math.PI * 2;
            const distance = origin.radius * 0.3;
            node.x = (origin.x ?? 0) + Math.cos(angle) * distance;
            node.y = (origin.y ?? 0) + Math.sin(angle) * distance;
            node.vx = 0;
            node.vy = 0;
        }

        this.simulation.nodes(this.visibleNodes);
        // Once the force has seen a link, its ends are the nodes rather than their ids.
        this.linkForce.links(
            this.links.filter(link => simulated.has(endId(link.source)) && simulated.has(endId(link.target)))
        );
        this.simulation.alpha(isFirst ? 1 : 0.5).restart();
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

function endId(end: string | number | LabelMapNode): string {
    return typeof end === 'object' ? end.id : String(end);
}

export type LabelMapNode = SimulationNodeDatum & {
    id: string;
    label: LabelMapLabel;
    radius: number;
};

/** Its ends are ids until the link force replaces them with the nodes. */
export type LabelMapLink = SimulationLinkDatum<LabelMapNode> & {
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