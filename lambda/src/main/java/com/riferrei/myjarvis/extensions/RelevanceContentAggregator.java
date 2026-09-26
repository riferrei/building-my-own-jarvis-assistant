package com.riferrei.myjarvis.extensions;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.scoring.ScoringModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.rag.content.aggregator.ContentAggregator;
import dev.langchain4j.rag.content.aggregator.ReciprocalRankFuser;
import dev.langchain4j.rag.query.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.IntStream;

public class RelevanceContentAggregator implements ContentAggregator {

    private static final Logger logger = LoggerFactory.getLogger(RelevanceContentAggregator.class);

    private final ScoringModel scoringModel;
    private final double minScore;

    private record ScoredContent(Content content, double score) {
    }

    private RelevanceContentAggregator(ScoringModel scoringModel, double minScore) {
        this.scoringModel = scoringModel;
        this.minScore = minScore;
    }

    @Override
    public List<Content> aggregate(Map<Query, Collection<List<Content>>> queryToContents) {
        if (queryToContents.isEmpty()) {
            return List.of();
        }
        if (queryToContents.size() > 1) {
            throw new IllegalArgumentException("Re-ranking expects exactly one query, got " + queryToContents.size());
        }

        var entry = queryToContents.entrySet().iterator().next();
        var contents = ReciprocalRankFuser.fuse(entry.getValue());
        if (contents.isEmpty()) {
            return List.of();
        }

        List<TextSegment> segments = contents.stream().map(Content::textSegment).toList();
        List<Double> scores = scoringModel.scoreAll(segments, entry.getKey().text()).content();

        var ranked = IntStream.range(0, contents.size())
                .mapToObj(i -> new ScoredContent(contents.get(i), scores.get(i)))
                .sorted(Comparator.comparingDouble(ScoredContent::score).reversed())
                .toList();

        var relevant = ranked.stream().filter(scored -> scored.score() >= minScore).toList();
        if (!relevant.isEmpty()) {
            logger.debug("Kept {} of {} contents scored at or above {}", relevant.size(), ranked.size(), minScore);
            return relevant.stream().map(RelevanceContentAggregator::withScore).toList();
        }

        var top = ranked.getFirst();
        logger.debug("No content scored at or above {}; keeping top content scored {}", minScore, top.score());
        return List.of(withScore(top));
    }

    private static Content withScore(ScoredContent scored) {
        Map<ContentMetadata, Object> metadata = new LinkedHashMap<>(scored.content().metadata());
        metadata.put(ContentMetadata.RERANKED_SCORE, scored.score());
        return Content.from(scored.content().textSegment(), metadata);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private ScoringModel scoringModel;
        private Double minScore;

        public Builder scoringModel(ScoringModel value) {
            this.scoringModel = value;
            return this;
        }

        public Builder minScore(double value) {
            this.minScore = value;
            return this;
        }

        public RelevanceContentAggregator build() {
            Objects.requireNonNull(scoringModel, "scoringModel is required");
            Objects.requireNonNull(minScore, "minScore is required");
            return new RelevanceContentAggregator(scoringModel, minScore);
        }
    }
}
