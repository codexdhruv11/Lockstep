package com.lockstep.analysis;

import java.util.List;

/**
 * What one logical write actually costs.
 *
 * <p>An INSERT of one row is never one write. It is a heap tuple, an entry in every index whose
 * predicate the row matches, write-ahead log records for all of it, occasionally a full page
 * image, and eventually an fsync. The ratio between the row's own size and the bytes that reach
 * the log is the amplification, and it is the number that decides whether the read path or the
 * write path is the ceiling.
 *
 * <p>The WAL counters in {@code pg_stat_wal} are <strong>cluster-wide</strong>. They are not per
 * table and not even per database, so anything else writing anywhere on the server during the run
 * is included. This is a stronger caveat than the one on the block counters, and the report says
 * so rather than implying the bytes are all ours.
 */
public record WriteAmplification(
        boolean available,
        String unavailableReason,
        long walBytes,
        long walRecords,
        long walFullPageImages,
        long walBuffersFull,
        long walSyncs,
        long walSyncTimeMicros,
        boolean walTimingTracked,
        List<Relation> relations) {

    public WriteAmplification {
        relations = relations == null ? List.of() : List.copyOf(relations);
    }

    public static WriteAmplification unavailable(String reason) {
        return new WriteAmplification(false, reason, 0, 0, 0, 0, 0, 0, false, List.of());
    }

    /**
     * One table's logical write counts and its index load.
     *
     * <p>{@code partialIndexCount} matters because a partial index only takes an entry when the
     * row matches its predicate. The index count is therefore an upper bound on entries per
     * insert, not a measurement, and is reported as one.
     */
    public record Relation(
            String name,
            long inserts,
            long updates,
            long deletes,
            long hotUpdates,
            int indexCount,
            int partialIndexCount,
            long bytesPerRow,
            long tableBytesGrown,
            long indexBytesGrown) {

        public Relation(String name, long inserts, long updates, long deletes, long hotUpdates,
                int indexCount, int partialIndexCount, long bytesPerRow) {
            this(name, inserts, updates, deletes, hotUpdates, indexCount, partialIndexCount,
                    bytesPerRow, 0, 0);
        }

        /**
         * Bytes the heap actually grew by per row inserted — measured, and preferred over
         * {@code bytesPerRow}, which is derived from {@code reltuples} and is stale on a table
         * that was analysed before the rows arrived. A table created and analysed while empty
         * reports a row size of zero forever until the next ANALYZE.
         */
        public long onDiskBytesPerInsert() {
            return inserts <= 0 || tableBytesGrown <= 0 ? 0 : tableBytesGrown / inserts;
        }

        /** Bytes the indexes grew by per row inserted — the index tax, on disk. */
        public long indexBytesPerInsert() {
            return inserts <= 0 || indexBytesGrown <= 0 ? 0 : indexBytesGrown / inserts;
        }

        /**
         * How many bytes of index were written for each byte of row. Negative when it cannot be
         * derived rather than 0, which would read as "the indexes cost nothing".
         */
        public double indexTaxRatio() {
            long table = onDiskBytesPerInsert();
            long index = indexBytesPerInsert();
            return table <= 0 || index <= 0 ? -1 : (double) index / table;
        }

        /** The best available per-row size: measured growth first, the planner's estimate after. */
        public long effectiveBytesPerRow() {
            long measured = onDiskBytesPerInsert();
            return measured > 0 ? measured : bytesPerRow;
        }

        public long logicalWrites() {
            return inserts + updates + deletes;
        }

        /**
         * The fraction of updates that avoided touching any index. A HOT update rewrites the row
         * in place and leaves every index alone, so a low fraction on a heavily indexed table is
         * where write cost hides.
         */
        public double hotUpdateFraction() {
            return updates <= 0 ? -1 : (double) hotUpdates / updates;
        }

        public boolean heavilyIndexed() {
            return indexCount >= 10;
        }
    }

    public long totalLogicalWrites() {
        long total = 0;
        for (Relation relation : relations) {
            total += relation.logicalWrites();
        }
        return total;
    }

    public boolean hasWrites() {
        return totalLogicalWrites() > 0;
    }

    public long walBytesPerWrite() {
        long writes = totalLogicalWrites();
        return writes <= 0 ? 0 : walBytes / writes;
    }

    public double walRecordsPerWrite() {
        long writes = totalLogicalWrites();
        return writes <= 0 ? 0 : (double) walRecords / writes;
    }

    public double fullPageImagesPerWrite() {
        long writes = totalLogicalWrites();
        return writes <= 0 ? 0 : (double) walFullPageImages / writes;
    }

    public double syncsPerWrite() {
        long writes = totalLogicalWrites();
        return writes <= 0 ? 0 : (double) walSyncs / writes;
    }

    /**
     * WAL bytes per write divided by the row's own size — how many times over the data was
     * written. Negative when the row size is unknown, rather than 0, which would read as
     * "no amplification".
     */
    public double amplificationFactor() {
        long perWrite = walBytesPerWrite();
        long rowBytes = dominantRowBytes();
        if (perWrite <= 0 || rowBytes <= 0) {
            return -1;
        }
        return (double) perWrite / rowBytes;
    }

    /** The row size of whichever relation took the most logical writes. */
    private long dominantRowBytes() {
        long best = 0;
        long bestWrites = -1;
        for (Relation relation : relations) {
            if (relation.logicalWrites() > bestWrites && relation.effectiveBytesPerRow() > 0) {
                bestWrites = relation.logicalWrites();
                best = relation.effectiveBytesPerRow();
            }
        }
        return best;
    }

    /**
     * Full page images are written for a page's first change after a checkpoint, so a short run
     * that happens to straddle one sees a burst of them and a misleadingly high byte count. Worth
     * saying when they are a large share.
     */
    public boolean fullPageImagesDominate() {
        return walRecords > 0 && walFullPageImages > walRecords * 0.2;
    }

    public List<Relation> byWritesDescending() {
        return relations.stream()
                .filter(relation -> relation.logicalWrites() > 0)
                .sorted((a, b) -> Long.compare(b.logicalWrites(), a.logicalWrites()))
                .toList();
    }
}
