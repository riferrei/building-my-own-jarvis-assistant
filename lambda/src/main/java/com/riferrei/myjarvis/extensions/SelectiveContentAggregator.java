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
import java.util.Optional;
import java.util.Set;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class SelectiveContentAggregator implements ContentAggregator {

    private static final Logger logger = LoggerFactory.getLogger(SelectiveContentAggregator.class);

    private final ScoringModel scoringModel;
    private final double minScore;
    private final double fallbackRatio;
    private final double fallbackMinScore;
    private final List<String> groupKeys;
    private final Pattern keepPattern;

    private record ScoredContent(Content content, double score) {
    }

    private SelectiveContentAggregator(ScoringModel scoringModel, double minScore, double fallbackRatio,
                                       double fallbackMinScore, List<String> groupKeys, Pattern keepPattern) {
        this.scoringModel = scoringModel;
        this.minScore = minScore;
        this.fallbackRatio = fallbackRatio;
        this.fallbackMinScore = fallbackMinScore;
        this.groupKeys = groupKeys;
        this.keepPattern = keepPattern;
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

        var queryMatches = matchesOf(entry.getKey().text());
        var relevant = ranked.stream()
                .filter(scored -> scored.score() >= minScore || sharesMatch(scored, queryMatches))
                .toList();
        if (relevant.isEmpty()) {
            double floor = Math.max(ranked.getFirst().score() * fallbackRatio, fallbackMinScore);
            relevant = Stream.concat(Stream.of(ranked.getFirst()),
                            ranked.stream().skip(1).filter(scored -> scored.score() >= floor))
                    .toList();
            logger.debug("No content scored at or above {}; keeping {} contents scored at or above {}",
                    minScore, relevant.size(), floor);
        } else {
            logger.debug("Kept {} of {} contents scored at or above {} or matching {}",
                    relevant.size(), ranked.size(), minScore, queryMatches);
        }

        var grouped = withSameGroup(relevant, ranked);
        if (grouped.size() > relevant.size()) {
            logger.debug("Added {} contents sharing {} with the kept ones", grouped.size() - relevant.size(), groupKeys);
        }
        return grouped.stream().map(SelectiveContentAggregator::withScore).toList();
    }

    private Set<String> matchesOf(String text) {
        if (keepPattern == null) {
            return Set.of();
        }
        return keepPattern.matcher(text).results().map(MatchResult::group).collect(Collectors.toSet());
    }

    private boolean sharesMatch(ScoredContent scored, Set<String> queryMatches) {
        return !queryMatches.isEmpty()
                && matchesOf(scored.content().textSegment().text()).stream().anyMatch(queryMatches::contains);
    }

    private List<ScoredContent> withSameGroup(List<ScoredContent> kept, List<ScoredContent> ranked) {
        if (groupKeys.isEmpty()) {
            return kept;
        }
        var groups = kept.stream().map(this::groupOf).flatMap(Optional::stream).collect(Collectors.toSet());
        return ranked.stream()
                .filter(scored -> kept.contains(scored) || groupOf(scored).filter(groups::contains).isPresent())
                .toList();
    }

    private Optional<List<String>> groupOf(ScoredContent scored) {
        var metadata = scored.content().textSegment().metadata();
        var values = groupKeys.stream().map(metadata::getString).toList();
        return values.contains(null) ? Optional.empty() : Optional.of(values);
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
        private double fallbackRatio = 1.0;
        private double fallbackMinScore = 0.0;
        private List<String> groupKeys = List.of();
        private Pattern keepPattern;

        public Builder scoringModel(ScoringModel value) {
            this.scoringModel = value;
            return this;
        }

        public Builder minScore(double value) {
            this.minScore = value;
            return this;
        }

        public Builder fallbackRatio(double value) {
            this.fallbackRatio = value;
            return this;
        }

        public Builder fallbackMinScore(double value) {
            this.fallbackMinScore = value;
            return this;
        }

        public Builder groupBy(String... keys) {
            this.groupKeys = List.of(keys);
            return this;
        }

        public Builder keepMatching(String value) {
            this.keepPattern = Pattern.compile(value);
            return this;
        }

        public SelectiveContentAggregator build() {
            Objects.requireNonNull(scoringModel, "scoringModel is required");
            Objects.requireNonNull(minScore, "minScore is required");
            return new SelectiveContentAggregator(scoringModel, minScore, fallbackRatio, fallbackMinScore, groupKeys, keepPattern);
        }
    }
}
