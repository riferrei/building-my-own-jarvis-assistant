# CLAUDE.md

Implementation best practices for this project. These describe the patterns the
codebase already follows — match them when adding or changing code. For setup,
deployment, and account configuration, see [README.md](README.md); this file is
about *how the code is written*, not how it is run.

The deliverable is an **Amazon Alexa custom skill** whose backend is a **Java 21
AWS Lambda** built with **LangChain4J**, provisioned with **Terraform** and the
**ASK CLI**. Java sources live under `lambda/`, infrastructure under
`infrastructure/`.

---

## Architecture overview

Request flow for a spoken utterance:

```
Alexa → Lambda (MyJarvisStreamHandler)
  → UserValidationInterceptor (resolves/creates user, stashes user context)
  → <Intent>Handler (extracts slots, builds system prompt, delegates)
    → ChatAssistantService (RAG orchestration)
      → ChatModel / EmbeddingStore / SessionMemoryStore / tools
  → HandlerHelper.buildAlexaResponse (uniform Alexa response)
```

Packages under `com.riferrei.myjarvis`:

- `handlers/` — one class per Alexa intent (`RequestHandler`), plus request/exception glue.
- `services/` — business logic and external integrations (chat, memory, cache, users, reminders); also the `AiServices` interfaces.
- `tools/` — LangChain4J `@Tool` classes the LLM can call.
- `extensions/` — custom LangChain4J SPI implementations (`ChatMemoryStore`, `ChatMemory`, `ScoringModel`, `ContentAggregator`).
- `helpers/` — cross-cutting utilities, records, the `Constants` enum, the interceptor, the exception + handler, and the chat-model `LatencyListener`.

---

## Dependency injection & lifecycle (most important)

`MyJarvisStreamHandler` is the **single composition root**. It is the only place
that constructs model/service/store/tool beans, and it does so as
`static final` fields (reused across warm Lambda invocations). Everything else
receives ready-made references through its **constructor**.

- **Do** build every bean (chat model, scoring model, embedding model, embedding store, `DynamoDbClient`, `UserService`, etc.) in `MyJarvisStreamHandler` and thread it into handlers/services via constructor parameters. The users table (`UserService`) and the session-memory store share one injected `DynamoDbClient`; the user-memory *vector* store (`DynamoDbEmbeddingStore`) manages its own client internally and is not routed through it. `SessionMemoryStore` is a `ChatMemoryStore` SPI with no dependency on our own service classes — it talks to DynamoDB directly through the injected `DynamoDbClient` + table name + TTL passed to its builder.
- **Do not** call `.builder()` / `new` on a dependency *inside* a service, handler, or tool. Those classes declare `private final` fields and assign them from constructor arguments only.
- Share a single instance where two collaborators need the same thing. The knowledge-base `EmbeddingModel` and `EmbeddingStore` are built once and injected into **both** the ingestion handler and `ChatAssistantService`, because ingestion and retrieval must use the identical embedding model.
- The only object created locally inside a class is one with no external config that genuinely belongs to that class (e.g. `KnowledgeBaseIntentHandler` creates its own `AmazonS3` client). Prefer injection; reserve local construction for truly internal, config-free helpers.

## Builder pattern for configurable beans

Services/extensions that take several config values expose a static `builder()`
returning a nested `public static class Builder` with fluent setters and a
`build()` that validates required fields with `Objects.requireNonNull(x, "x is
required")`. See `UserService.Builder` and `SessionMemoryStore.Builder`.
Constructors of such classes are `private`; instances come only through the
builder. Simple beans that just wrap collaborators (e.g. `ReminderService`) use a
plain public constructor instead — reach for a builder only when there are
multiple, optional, or validated parameters.

---

## Intent handlers

- One handler class per intent, implementing `com.amazon.ask.dispatcher.request.handler.RequestHandler`, registered in `MyJarvisStreamHandler.getSkill()`.
- `canHandle` matches on `Predicates.intentName(<INTENT_CONSTANT>)`; the constant lives in `Constants`.
- `handle` stays short and orchestrational: extract inputs → delegate → build response. Push detail into small `private` methods (`extractQuery`, `processConversation`, `buildResponse`, …).
- Extract the shared request state once via `HandlerHelper.extractRequestContext(handlerInput)` into a `RequestContext` record; read slots defensively (null/blank checks) and return `Optional` from extraction helpers.
- System prompts are `private static final String` text blocks (`"""…"""`). When they need runtime values (e.g. timezone) use a `%s` placeholder filled with `String.format` — never string-concatenate untrusted values into the prompt ad hoc.
- Always build the Alexa response through `HandlerHelper.buildAlexaResponse(...)` so speech + card + `shouldEndSession` stay uniform. Never hand-roll the `responseBuilder` chain in a handler except for genuinely special cases (e.g. the exception handler's reprompt).
- Every handler defines its own user-facing fallback/error strings as constants and **never lets an exception escape** to the user — catch, log, and return a friendly spoken message.
- Multi-turn flows (e.g. reminder confirmation) pass state through `handlerInput.getAttributesManager().getSessionAttributes()` and are completed by `YesIntentHandler`/`NoIntentHandler`. Set `shouldEndSession=false` when expecting a follow-up.

## LangChain4J usage

- Declarative AI interfaces (`BasicChatAssistant`, `ContextualChatAssistant`) live in `services/` and use `@SystemMessage` / `@UserMessage` / `@V` templating; build them with `AiServices.builder(...)`.
- RAG is assembled in `ChatAssistantService.createRetrievalAugmentor(...)`: `CompressingQueryTransformer` → `DefaultQueryRouter` (every query goes to every retriever; relevance filtering is the aggregator's job, not an LLM routing decision) → `EmbeddingStoreContentRetriever` / custom `ContentRetriever` lambdas → `extensions/RelevanceContentAggregator` (reranks through `extensions/BedrockScoringModel`, a `ScoringModel` that calls the model-agnostic Amazon Bedrock Rerank API (`bedrock-agent-runtime`) in `bedrock_rerank_region` with whichever rerank model `bedrock_rerank_model_id` names, Cohere Rerank 3.5 by default; score scales differ between rerank models, so `minScore` must be retuned when the model changes; keeps contents scored at or above `minScore`, falling back to the single top-ranked content when none qualify) → `DefaultContentInjector` (explicit prompt template). Add a new source by passing its retriever to the `DefaultQueryRouter`.
- Tools are plain classes with `@Tool("description")` methods and `@P("…")` parameter docs; they delegate to an injected service and log their inputs. Register tool instances in the `List<Object>` passed to `ChatAssistantService`. Per-request values a tool needs (the device timezone the handler resolved into `RequestContext`, and the userId, which no current tool uses) reach it through `InvocationParameters` set by `ChatAssistantService` (keys `TIME_ZONE_PARAM`, `USER_ID_PARAM`), never through the model; tools hold no per-request state. Values the model only needs to read, like the current date and time or the dates of the next seven days (`HandlerHelper.currentDateTime`, `HandlerHelper.upcomingDates`), are computed per request from that timezone and go into the system prompt via `%s` rather than a forced tool call.
- When retrieval and ingestion share an embedding space, they **must** use the same `EmbeddingModel` instance (injected from the composition root).
- **Multi-tenant isolation is a retrieval invariant.** User memories share one `DynamoDbEmbeddingStore`; a user's data is isolated *only* by the `ownerId` metadata attribute (declared via `inlineFilterAttributes`) plus a **mandatory** `ownerId` equality filter on every retrieval (`EmbeddingStoreContentRetriever.filter(...)`). A retriever built without that filter would leak every user's memories. The write path (`RememberIntentHandler` → `services/UserMemoryService`) and read path (`ChatAssistantService`) must derive the ownerId identically — both go through `helpers/OwnerId.sanitize(...)`, the single source of truth for the isolation key, so they can never drift. The write path never takes the userId from the model: the handler passes the userId from `RequestContext`, and the model only returns the memory text in its JSON answer.
- **Time-bound memories expire.** The remember prompt returns `memory` and `time_bound`; for a time-bound, non-recurring memory, `UserMemoryService` stores an `expiresAt` metadata attribute (epoch seconds, the event's `schedule` in the device timezone). DynamoDB vector search allows only equality filters, so expiry can't be a retrieval filter: `ChatAssistantService` drops expired contents after retrieval (`unexpired(...)`), and DynamoDB TTL on `expiresAt` deletes them from the table and its vector index later. Memories without `expiresAt` never expire.

## External integrations (services)

- HTTP integrations use the JDK `java.net.http.HttpClient` (HTTP/2, shared `static` instance) with a shared Jackson `ObjectMapper` (`findAndRegisterModules()`), not a third-party HTTP library.
- Centralize request building (`buildJsonRequest(uri, body, method)`) and response parsing; read JSON with `objectMapper.readTree(...).path(...)` and treat missing nodes gracefully.
- **Integrations degrade, they don't crash.** On any error, log via SLF4J and return a safe empty value (`Optional.empty()`, `List.of()`, `false`) so a downstream failure never breaks the spoken interaction. Reserve thrown exceptions for programmer errors (e.g. unsupported HTTP method) and the deliberate `UserDoesNotExistException` control-flow signal.
- Check status codes against `org.apache.http.HttpStatus` constants; branch on the ones you expect and log unexpected ones.
- Alexa user/person/session IDs are long, so hash them to 64 hex chars with SHA-256 before using them as storage keys. Sanitize identifiers at the integration boundary, not in handlers. The user-record PK and the user-memory ownerId both use the shared `helpers/OwnerId.sanitize(...)` (single source of truth, because a user's read and write paths must derive the same key); the session-memory partition key uses `SessionMemoryStore`'s own class-local `sanitizeSessionId` (a distinct isolation key, not an ownerId).

## Custom LangChain4J extensions

Custom SPI implementations (e.g. `SessionMemoryStore implements ChatMemoryStore`)
go in `extensions/`, are built via the builder pattern, and encapsulate their own
quirks (message-role filtering, delta-only writes).
Keep this backend-specific logic inside the extension, out of handlers/services.
A `ChatMemoryStore` only persists and loads messages; eviction (trimming to a
context window) is the `ChatMemory`'s responsibility, never the store's.

---

## Java conventions

- **Java 21.** Use modern idioms already in the codebase: records for immutable data (`RequestContext`, `UserContext`, private response records with `@JsonProperty`), switch expressions with pattern matching, text blocks for prompts, `var` for locals with an obvious RHS type.
- Prefer `Optional` over returning `null` from methods that may have no result; guard against `null`/blank at boundaries.
- Logging is SLF4J: `private static final Logger logger = LoggerFactory.getLogger(X.class);`. Log at `info` for normal milestones, `debug` for verbose detail, `warn`/`error` for problems (pass the exception as the last arg). Never `printStackTrace` or `System.out`.
- All string/config constants live in the `Constants` enum (`INSTANCE` singleton, `public static final` fields). Read environment variables **only** in `Constants`, applying the established default pattern: `(getenv(X) == null || getenv(X).isEmpty()) ? default : getenv(X)`. Required values (keys, endpoints) are read with a bare `System.getenv(...)`. Classes import constants statically (`import static …Constants.*`).
- Class-internal DTOs (like a handler's AI-response shape) are `private record`s nested in the class that owns them.

## Adding a new environment variable

1. Add the constant to `Constants` (with a default if optional).
2. Read it in `MyJarvisStreamHandler` when constructing the relevant bean.
3. Wire it in `infrastructure/my-jarvis-alexa-skill.tf` under the Lambda `environment.variables`.
4. Declare a matching Terraform `variable` in `variables.tf` and add it to `terraform.tfvars.example`.

Keep code and infrastructure in lockstep: **any code change that reads new config, changes the handler/entry point, or needs new AWS permissions must be reflected in `infrastructure/` in the same change.**

## Infrastructure (Terraform)

- One file drives the skill: `infrastructure/my-jarvis-alexa-skill.tf`; inputs in `variables.tf`; example values in `terraform.tfvars.example` (never commit real `terraform.tfvars`).
- The build is wired into Terraform: a `null_resource` runs `mvn clean package`, the JAR is uploaded to S3, and the Lambda's `handler` points at `com.riferrei.myjarvis.MyJarvisStreamHandler::handleRequest`. If you rename the entry point or change the artifact coordinates in `lambda/pom.xml`, update the `.tf` references too.
- IAM is least-privilege and resource-scoped (e.g. S3 and S3 Vectors actions scoped to specific bucket/index ARNs). Add only the actions a change actually needs, scoped to the new resource's ARN. Amazon Bedrock follows the same rule: `bedrock:InvokeModel` is granted on the chat model's global inference profile plus its in-region and Region-agnostic (`arn:aws:bedrock:::foundation-model/...`) foundation-model ARNs, conditioned on `bedrock:InferenceProfileArn`, on the in-region Titan embedding model ARN, and on the rerank model's foundation-model ARN in `bedrock_rerank_region` (which defaults to the Lambda's Region and exists because rerank models are offered in only a few Regions, without cross-Region inference). The Rerank API additionally needs `bedrock:Rerank`, which has no resource types or condition keys, so it is granted on `*` limited by `aws:RequestedRegion` to `bedrock_rerank_region`; the model-scoped `InvokeModel` statement is what actually gates which model can be used.
- Prefer provisioning storage in Terraform (as with the S3 Vectors bucket/index and the two **plain** DynamoDB tables — the users table and the session-memory table, both `aws_dynamodb_table`, `PAY_PER_REQUEST`). The session-memory table declares a DynamoDB TTL on the `expiresAt` attribute; because TTL deletion is imprecise (items linger up to ~48h past expiry), `SessionMemoryStore` *also* filters expired events at read time so the effective session lifetime matches `session_memory_ttl_minutes` (default 5). The user-memory DynamoDB *vector* table is the one piece not modeled as a native resource: the AWS provider has no resource for a DynamoDB vector table/index, so a `null_resource` creates it with the AWS CLI (`create-table --vector-indexes`, declaring the `ownerId` inline filter in both `AttributeDefinitions` and the `SearchSchema`) and deletes it on destroy. A second `null_resource` turns on DynamoDB TTL on its `expiresAt` attribute (skipped when already enabled) and reruns whenever the table is recreated. Its schema (key `id`, vector `embedding`, COSINE, `embedding_dimensions`, `ownerId` inline filter) must match what `DynamoDbEmbeddingStore` expects, and changing any trigger (table/index name, dimensions, region) replaces the table and drops every stored memory. Terraform does not detect drift on it. The Lambda uses `createTableIfNotExists(false)` and so needs no `CreateTable`/`DescribeTable` permission. Revisit this if the provider adds native support.
- The Redis Agent Memory REST API is fully retired: user records live in the plain users table (accessed directly by `UserService`) and short-term chat memory lives in the plain session-memory table (accessed directly by the `SessionMemoryStore` SPI). Semantic caching (formerly Redis LangCache) has been removed; there is currently no response cache.
- Mark secret variables `sensitive = true`. Give resources `default`s only when a sensible one exists.
- Validate infra changes with `terraform fmt` + `terraform validate` (init with `-backend=false` is enough to validate without cloud credentials).

## Build & verify

- Build/package the Lambda from `lambda/`: `mvn clean package` (Maven Shade produces the fat JAR). A green package is the bar before committing code changes.
- Dependency versions are governed by imported BOMs (`jackson-bom`, the AWS SDK `bom`, `langchain4j-bom`, `langchain4j-community-bom`) and shared `${…version}` properties in `pom.xml` — prefer letting the BOM set versions over pinning per-dependency. The Jackson and AWS SDK BOMs keep every transitive module of those families on one version. Keep `aws.sdk.version` on the minor line the `langchain4j-community` modules are built against (AWS SDK minor releases may be backward-incompatible); take patch releases freely. When adding a LangChain4J artifact, confirm the version exists on Maven Central rather than guessing.
- There is no automated test suite; verification is a clean build plus `terraform validate`. Don't claim behavior is verified beyond what those checks actually cover.
