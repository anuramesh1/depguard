package dev.depguard.rag.ingest;

import dev.depguard.rag.AdvisoryStore;
import dev.depguard.rag.AdvisoryStore.EmbeddedChunk;
import dev.depguard.rag.KnowledgeChunk;
import dev.depguard.rag.RagProperties;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;

/**
 * Builds or refreshes the knowledge base: fetch guides and advisories, embed what changed, store it.
 *
 * <p>Re-running is cheap: each chunk carries a content hash, and only new or changed chunks are embedded
 * (the embedding calls are the only part that costs money). A first full run embeds ~7,500 chunks for
 * about $0.05 with Titan Text Embeddings v2.
 */
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);
    /** Embed and store in batches, so progress is saved (and logged) as the run goes. */
    static final int BATCH_SIZE = 100;

    private final RagProperties.Ingest settings;
    private final GuideFeed guideFeed;
    private final OsvAdvisoryFeed osvFeed;
    private final EmbeddingModel embeddingModel;
    private final AdvisoryStore store;

    public IngestService(RagProperties.Ingest settings, GuideFeed guideFeed, OsvAdvisoryFeed osvFeed,
                         EmbeddingModel embeddingModel, AdvisoryStore store) {
        this.settings = settings;
        this.guideFeed = guideFeed;
        this.osvFeed = osvFeed;
        this.embeddingModel = embeddingModel;
        this.store = store;
    }

    /**
     * @param total     chunks in the sources right now
     * @param embedded  new or changed chunks embedded and stored
     * @param unchanged chunks skipped because their hash matched
     * @param failed    chunks whose embedding failed (a re-run retries them)
     * @param deleted   stored chunks no longer in the sources
     */
    public record Report(int total, int embedded, int unchanged, int failed, int deleted, long durationMs) {
    }

    public Report ingest() throws IOException, InterruptedException {
        long start = System.nanoTime();
        store.createSchema();

        List<KnowledgeChunk> chunks = new ArrayList<>();
        boolean complete = settings.maxAdvisories() == 0; // only a full run may delete what's missing
        for (RagProperties.Guide guide : settings.guides()) {
            try {
                List<KnowledgeChunk> guideChunks = guideFeed.fetch(guide);
                log.info("Guide {}: {} sections", guide.id(), guideChunks.size());
                chunks.addAll(guideChunks);
            } catch (IOException e) {
                log.warn("Skipping guide {}: {}", guide.id(), e.getMessage());
                complete = false;
            }
        }
        List<KnowledgeChunk> advisories = osvFeed.fetch(settings.osvDumpUrl(), settings.maxAdvisories());
        log.info("OSV export: {} Maven advisories", advisories.size());
        chunks.addAll(advisories);

        Map<String, String> storedHashes = store.contentHashes();
        List<KnowledgeChunk> changed = chunks.stream()
                .filter(chunk -> !chunk.contentHash().equals(storedHashes.get(chunk.id())))
                .toList();
        log.info("{} chunks in total, {} new or changed, {} unchanged",
                chunks.size(), changed.size(), chunks.size() - changed.size());

        int failed = embedAndStore(changed);

        int deleted = 0;
        if (complete) {
            Set<String> currentIds = chunks.stream().map(KnowledgeChunk::id).collect(Collectors.toSet());
            List<String> stale = storedHashes.keySet().stream().filter(id -> !currentIds.contains(id)).toList();
            if (!stale.isEmpty()) {
                store.delete(stale);
                deleted = stale.size();
            }
        }
        return new Report(chunks.size(), changed.size() - failed, chunks.size() - changed.size(), failed, deleted,
                (System.nanoTime() - start) / 1_000_000);
    }

    /** Embeds chunks in parallel (bounded, to stay under Bedrock's rate limit) and stores each batch. */
    private int embedAndStore(List<KnowledgeChunk> chunks) throws InterruptedException {
        int failed = 0;
        Semaphore inFlight = new Semaphore(settings.concurrency());
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int from = 0; from < chunks.size(); from += BATCH_SIZE) {
                List<KnowledgeChunk> batch = chunks.subList(from, Math.min(from + BATCH_SIZE, chunks.size()));
                List<Future<EmbeddedChunk>> futures = batch.stream()
                        .map(chunk -> executor.submit(() -> embed(chunk, inFlight)))
                        .toList();
                List<EmbeddedChunk> embedded = new ArrayList<>();
                for (Future<EmbeddedChunk> future : futures) {
                    embedded.add(resultOrNull(future));
                }
                List<EmbeddedChunk> ok = embedded.stream().filter(Objects::nonNull).toList();
                failed += batch.size() - ok.size();
                store.upsert(ok);
                log.info("Embedded and stored {}/{}", Math.min(from + BATCH_SIZE, chunks.size()), chunks.size());
            }
        }
        return failed;
    }

    private EmbeddedChunk embed(KnowledgeChunk chunk, Semaphore inFlight) throws InterruptedException {
        inFlight.acquire();
        try {
            return new EmbeddedChunk(chunk, embeddingModel.embed(chunk.textForEmbedding()));
        } catch (RuntimeException e) {
            log.warn("Embedding failed for {}: {}", chunk.id(), e.getMessage());
            return null;
        } finally {
            inFlight.release();
        }
    }

    private static EmbeddedChunk resultOrNull(Future<EmbeddedChunk> future) throws InterruptedException {
        try {
            return future.get();
        } catch (java.util.concurrent.ExecutionException e) {
            return null;
        }
    }
}
