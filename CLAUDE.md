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
    → ChatAssistantService (RAG orchestration + semantic cache)
      → ChatModel / EmbeddingStore / MemoryService / LangCacheService / tools
  → HandlerHelper.buildAlexaResponse (uniform Alexa response)
```

Packages under `com.riferrei.myjarvis`:

- `handlers/` — one class per Alexa intent (`RequestHandler`), plus request/exception glue.
- `services/` — business logic and external integrations (chat, memory, cache, users, reminders); also the `AiServices` interfaces.
- `tools/` — LangChain4J `@Tool` classes the LLM can call.
- `extensions/` — custom LangChain4J SPI implementations (`ChatMemoryStore`, `ChatMemory`).
- `helpers/` — cross-cutting utilities, records, the `Constants` enum, the interceptor, and the exception + handler.

---

## Dependency injection & lifecycle (most important)

`MyJarvisStreamHandler` is the **single composition root**. It is the only place
that constructs model/service/store/tool beans, and it does so as
`static final` fields (reused across warm Lambda invocations). Everything else
receives ready-made references through its **constructor**.

- **Do** build every bean (chat model, scoring model, embedding model, embedding store, `MemoryService`, `LangCacheService`, etc.) in `MyJarvisStreamHandler` and thread it into handlers/services via constructor parameters.
- **Do not** call `.builder()` / `new` on a dependency *inside* a service, handler, or tool. Those classes declare `private final` fields and assign them from constructor arguments only.
- Share a single instance where two collaborators need the same thing. The knowledge-base `EmbeddingModel` and `EmbeddingStore` are built once and injected into **both** the ingestion handler and `ChatAssistantService`, because ingestion and retrieval must use the identical embedding model.
- The only object created locally inside a class is one with no external config that genuinely belongs to that class (e.g. `KnowledgeBaseIntentHandler` creates its own `AmazonS3` client). Prefer injection; reserve local construction for truly internal, config-free helpers.

## Builder pattern for configurable beans

Services/extensions that take several config values expose a static `builder()`
returning a nested `public static class Builder` with fluent setters and a
`build()` that validates required fields with `Objects.requireNonNull(x, "x is
required")`. See `MemoryService.Builder` and `WorkingMemoryStore.Builder`.
Constructors of such classes are `private`; instances come only through the
builder. Simple beans that just wrap collaborators (e.g. `UserService`) use a
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
- RAG is assembled in `ChatAssistantService.createRetrievalAugmentor(...)`: `CompressingQueryTransformer` → `LanguageModelQueryRouter` (retriever→description map, `ROUTE_TO_ALL` fallback) → `EmbeddingStoreContentRetriever` / custom `ContentRetriever` lambdas → `ReRankingContentAggregator` (Cohere, `minScore`) → `DefaultContentInjector` (explicit prompt template). Keep the router's `Map<ContentRetriever,String>` shape when adding a source.
- `processQueryWithContext` is **cache-first**: consult `LangCacheService`, and on a miss run the model then write the response back to the cache.
- Tools are plain classes with `@Tool("description")` methods and `@P("…")` parameter docs; they delegate to an injected service and log their inputs. Register tool instances in the `List<Object>` passed to `ChatAssistantService`.
- When retrieval and ingestion share an embedding space, they **must** use the same `EmbeddingModel` instance (injected from the composition root).

## External integrations (services)

- HTTP integrations use the JDK `java.net.http.HttpClient` (HTTP/2, shared `static` instance) with a shared Jackson `ObjectMapper` (`findAndRegisterModules()`), not a third-party HTTP library.
- Centralize request building (`buildJsonRequest(uri, body, method)`) and response parsing; read JSON with `objectMapper.readTree(...).path(...)` and treat missing nodes gracefully.
- **Integrations degrade, they don't crash.** On any error, log via SLF4J and return a safe empty value (`Optional.empty()`, `List.of()`, `false`) so a downstream failure never breaks the spoken interaction. Reserve thrown exceptions for programmer errors (e.g. unsupported HTTP method) and the deliberate `UserDoesNotExistException` control-flow signal.
- Check status codes against `org.apache.http.HttpStatus` constants; branch on the ones you expect and log unexpected ones.
- Alexa user/person/session IDs exceed the 64-char limit of the memory backend, so hash them to 64 hex chars with SHA-256 before use (`sanitizeOwnerId` / `sanitizeSessionId`). Sanitize identifiers at the integration boundary, not in handlers.

## Custom LangChain4J extensions

Custom SPI implementations (e.g. `WorkingMemoryStore implements ChatMemoryStore`)
go in `extensions/`, are built via the builder pattern, and encapsulate their own
quirks (message-role filtering, context-window trimming, delta-only writes).
Keep this backend-specific logic inside the extension, out of handlers/services.

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
- IAM is least-privilege and resource-scoped (e.g. S3 and S3 Vectors actions scoped to specific bucket/index ARNs). Add only the actions a change actually needs, scoped to the new resource's ARN.
- Mark secret variables `sensitive = true`. Give resources `default`s only when a sensible one exists.
- Validate infra changes with `terraform fmt` + `terraform validate` (init with `-backend=false` is enough to validate without cloud credentials).

## Build & verify

- Build/package the Lambda from `lambda/`: `mvn clean package` (Maven Shade produces the fat JAR). A green package is the bar before committing code changes.
- Dependency versions are governed by imported BOMs (`langchain4j-bom`, `langchain4j-community-bom`) and shared `${…version}` properties in `pom.xml` — prefer letting the BOM set versions over pinning per-dependency. When adding a LangChain4J artifact, confirm the version exists on Maven Central rather than guessing.
- There is no automated test suite; verification is a clean build plus `terraform validate`. Don't claim behavior is verified beyond what those checks actually cover.
