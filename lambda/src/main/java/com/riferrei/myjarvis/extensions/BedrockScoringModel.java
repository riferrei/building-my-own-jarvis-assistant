package com.riferrei.myjarvis.extensions;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.scoring.ScoringModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.bedrockagentruntime.BedrockAgentRuntimeClient;
import software.amazon.awssdk.services.bedrockagentruntime.model.BedrockRerankingConfiguration;
import software.amazon.awssdk.services.bedrockagentruntime.model.BedrockRerankingModelConfiguration;
import software.amazon.awssdk.services.bedrockagentruntime.model.RerankDocument;
import software.amazon.awssdk.services.bedrockagentruntime.model.RerankDocumentType;
import software.amazon.awssdk.services.bedrockagentruntime.model.RerankQuery;
import software.amazon.awssdk.services.bedrockagentruntime.model.RerankQueryContentType;
import software.amazon.awssdk.services.bedrockagentruntime.model.RerankSource;
import software.amazon.awssdk.services.bedrockagentruntime.model.RerankSourceType;
import software.amazon.awssdk.services.bedrockagentruntime.model.RerankTextDocument;
import software.amazon.awssdk.services.bedrockagentruntime.model.RerankingConfiguration;
import software.amazon.awssdk.services.bedrockagentruntime.model.RerankingConfigurationType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public class BedrockScoringModel implements ScoringModel {

    private static final Logger logger = LoggerFactory.getLogger(BedrockScoringModel.class);
    private static final String MODEL_ARN_FORMAT = "arn:aws:bedrock:%s::foundation-model/%s";

    private final BedrockAgentRuntimeClient bedrockAgentRuntimeClient;
    private final String modelArn;

    private BedrockScoringModel(BedrockAgentRuntimeClient bedrockAgentRuntimeClient, String modelId) {
        this.bedrockAgentRuntimeClient = bedrockAgentRuntimeClient;
        this.modelArn = String.format(MODEL_ARN_FORMAT,
                bedrockAgentRuntimeClient.serviceClientConfiguration().region().id(), modelId);
    }

    @Override
    public Response<List<Double>> scoreAll(List<TextSegment> segments, String query) {
        if (segments.isEmpty()) {
            return Response.from(List.of());
        }

        var sources = segments.stream().map(BedrockScoringModel::toSource).toList();
        var scores = new ArrayList<>(Collections.nCopies(sources.size(), 0.0));

        long start = System.nanoTime();
        var results = bedrockAgentRuntimeClient.rerankPaginator(builder -> builder
                .queries(toQuery(query))
                .sources(sources)
                .rerankingConfiguration(toConfiguration(sources.size())))
                .results();

        for (var result : results) {
            var index = result.index();
            if (index != null && index >= 0 && index < scores.size() && result.relevanceScore() != null) {
                scores.set(index, result.relevanceScore().doubleValue());
            }
        }

        logger.info("Re-ranked {} documents with {} in {} ms",
                scores.size(), modelArn, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        return Response.from(scores);
    }

    private static RerankQuery toQuery(String query) {
        return RerankQuery.builder()
                .type(RerankQueryContentType.TEXT)
                .textQuery(RerankTextDocument.builder().text(query).build())
                .build();
    }

    private static RerankSource toSource(TextSegment segment) {
        return RerankSource.builder()
                .type(RerankSourceType.INLINE)
                .inlineDocumentSource(RerankDocument.builder()
                        .type(RerankDocumentType.TEXT)
                        .textDocument(RerankTextDocument.builder().text(segment.text()).build())
                        .build())
                .build();
    }

    private RerankingConfiguration toConfiguration(int numberOfResults) {
        return RerankingConfiguration.builder()
                .type(RerankingConfigurationType.BEDROCK_RERANKING_MODEL)
                .bedrockRerankingConfiguration(BedrockRerankingConfiguration.builder()
                        .numberOfResults(numberOfResults)
                        .modelConfiguration(BedrockRerankingModelConfiguration.builder()
                                .modelArn(modelArn)
                                .build())
                        .build())
                .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private BedrockAgentRuntimeClient bedrockAgentRuntimeClient;
        private String modelId;

        public Builder bedrockAgentRuntimeClient(BedrockAgentRuntimeClient value) {
            this.bedrockAgentRuntimeClient = value;
            return this;
        }

        public Builder modelId(String value) {
            this.modelId = value;
            return this;
        }

        public BedrockScoringModel build() {
            Objects.requireNonNull(bedrockAgentRuntimeClient, "bedrockAgentRuntimeClient is required");
            Objects.requireNonNull(modelId, "modelId is required");
            return new BedrockScoringModel(bedrockAgentRuntimeClient, modelId);
        }
    }
}
