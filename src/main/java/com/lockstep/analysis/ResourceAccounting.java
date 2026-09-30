package com.lockstep.analysis;

import java.util.List;

public record ResourceAccounting(
        boolean available,
        String unavailableReason,
        List<Relation> relations,
        long sharedBuffersBytes,
        long blockSizeBytes,
        long generatorBytesAllocated,
        long requests,
        long runDurationNanos) {

    public ResourceAccounting {
        relations = relations == null ? List.of() : List.copyOf(relations);
    }

    public static ResourceAccounting unavailable(String reason) {
        return new ResourceAccounting(false, reason, List.of(), 0, 0, 0, 0, 0);
    }

    public record Relation(
            String name,
            long liveRows,
            long tableBytes,
            long indexBytes,
            long blocksHit,
            long blocksRead) {

        public long totalBytes() {
            return tableBytes + indexBytes;
        }

        public long blocksTouched() {
            return blocksHit + blocksRead;
        }

        public long bytesPerRow() {
            return liveRows <= 0 ? 0 : tableBytes / liveRows;
        }

        public double hitRatio() {
            long touched = blocksTouched();
            return touched <= 0 ? -1 : (double) blocksHit / touched;
        }
    }

    public boolean hasRelations() {
        return !relations.isEmpty();
    }

    public long blocksPerRequest(Relation relation) {
        return requests <= 0 ? 0 : relation.blocksTouched() / requests;
    }

    public long bytesPerRequest(Relation relation) {
        return blocksPerRequest(relation) * blockSizeBytes;
    }

    public double achievedRatePerSecond() {
        double seconds = runDurationNanos / 1_000_000_000.0;
        return seconds <= 0 ? 0 : requests / seconds;
    }

    /**
     * Bytes of buffer traffic this relation cost per second at the rate actually achieved. The
     * number that turns a per-request figure into a capacity argument: a few megabytes a request
     * sounds survivable until it is multiplied by the request rate.
     */
    public double bufferBytesPerSecond(Relation relation) {
        return bytesPerRequest(relation) * achievedRatePerSecond();
    }

    /**
     * Heap allocated by <em>this</em> process per request — the generator's own cost, not the
     * target's. It says whether the load generator is itself under strain; it says nothing about
     * how much heap the target needs. The target's allocation has to come from the target's own
     * metrics or flight recording.
     */
    public long generatorBytesPerRequest() {
        return requests <= 0 ? 0 : generatorBytesAllocated / requests;
    }

    public double generatorAllocationBytesPerSecond() {
        double seconds = runDurationNanos / 1_000_000_000.0;
        return seconds <= 0 ? 0 : generatorBytesAllocated / seconds;
    }

    /**
     * The row count at which this table alone would exceed {@code shared_buffers}, or -1 when it
     * cannot be derived. A bound rather than a prediction: shared_buffers is shared with every
     * other relation and every other database in the cluster, so the real crossover comes earlier.
     */
    public long rowsAtSharedBuffersLimit(Relation relation) {
        long perRow = relation.bytesPerRow();
        if (perRow <= 0 || sharedBuffersBytes <= 0) {
            return -1;
        }
        return sharedBuffersBytes / perRow;
    }

    public boolean exceedsSharedBuffers(Relation relation) {
        return sharedBuffersBytes > 0 && relation.tableBytes() > sharedBuffersBytes;
    }

    /**
     * True when the run read more bytes from this relation per request than the relation's whole
     * table occupies — i.e. every request walks the table. The strongest single signal that a
     * summary or an index is missing.
     */
    public boolean readsWholeTablePerRequest(Relation relation) {
        long perRequest = bytesPerRequest(relation);
        return perRequest > 0 && relation.tableBytes() > 0
                && perRequest >= relation.tableBytes() * 0.9;
    }

    public List<Relation> byBytesTouchedDescending() {
        return relations.stream()
                .sorted((a, b) -> Long.compare(b.blocksTouched(), a.blocksTouched()))
                .toList();
    }
}
