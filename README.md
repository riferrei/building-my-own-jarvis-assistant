# Building my own J.A.R.V.I.S assistant

## Overview
This project demonstrates how to create a [J.A.R.V.I.S](https://en.wikipedia.org/wiki/J.A.R.V.I.S.) assistant deployed as an Alexa skill. Built using Java, LangChain4J, and AWS, it enables Alexa to recall past conversations and deliver contextual, intelligent responses. It showcases how to implement a memory layer for AI assistants, enriching the natural language experience through state persistence and fast retrieval.

## Table of Contents
- [Setup](#setup)
- [Testing the Assistant](#testing-the-assistant)
- [Architecture](#architecture)
- [Known Issues](#known-issues)
- [Resources](#resources)
- [Maintainers](#maintainers)
- [License](#license)

## Setup

### Dependencies
- [Java 21+](https://www.oracle.com/java/technologies/downloads)
- [Maven 3.9+](https://maven.apache.org/install.html)
- [Terraform](https://developer.hashicorp.com/terraform/install)
- [AWS CLI](https://github.com/aws/aws-cli)
- [ASK CLI](https://github.com/alexa/ask-cli)
- [JQ](https://jqlang.org/)
- [SED](https://formulae.brew.sh/formula/gnu-sed)

### Account Requirements
| Account                                                  | Description                                                                  |
|:---------------------------------------------------------|:-----------------------------------------------------------------------------|
| [AWS account](https://aws.amazon.com/account)            | Required to create Lambda, IAM, and CloudWatch resources, and to call Amazon Bedrock. |
| [Amazon developer account](https://developer.amazon.com) | This is needed to register, deploy, and test Alexa skills.                   |
| [Cohere](https://cohere.com)                             | Re-ranking model used to keep only the relevant memories and knowledge-base content in the context. |

### Configuration

#### AWS Setup
1. Install the AWS CLI: [Installation Guide](https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html)
2. Configure your credentials:
   ```sh
   aws configure
   ```

#### Amazon Developer Account
1. Install the ASK CLI: [Installation Guide](https://developer.amazon.com/en-US/docs/alexa/smapi/quick-start-alexa-skills-kit-command-line-interface.html)
2. Configure your credentials:
   ```sh
   ask configure
   ```

#### Terraform Configuration
1. Create your variables file:
   ```sh
   cp infrastructure/terraform.tfvars.example infrastructure/terraform.tfvars
   ```
2. Edit `infrastructure/terraform.tfvars` with your information:

| Variable                       | Description                                                                            |
|:-------------------------------|:---------------------------------------------------------------------------------------|
| `application_prefix`           | Prefix used for naming AWS resources (Lambda function, IAM role, artifacts bucket, etc.). |
| `bedrock_chat_model_id`        | Amazon Bedrock global inference profile for the chat model. Optional; defaults to `global.anthropic.claude-sonnet-5`. |
| `bedrock_chat_max_tokens`      | Maximum output tokens for the chat model. Optional; defaults to `4096`.                |
| `cohere_api_key`               | API key used to re-rank retrieved memories and knowledge-base content with Cohere.     |
| `cohere_model_name`            | Cohere re-ranking model. Optional; defaults to `rerank-multilingual-v3.0`.             |
| `knowledge_base_bucket_name`   | Name of the S3 bucket used to upload knowledge base documents.                         |
| `create_knowledge_base_bucket` | Whether Terraform creates the knowledge base bucket or uses an existing one. Optional; defaults to `true`. |
| `s3_vectors_bucket_name`       | S3 Vectors vector bucket that stores the knowledge base embeddings.                    |
| `s3_vectors_index_name`        | S3 Vectors index within the vector bucket. Optional; defaults to `knowledge-base`.     |
| `embedding_model_name`         | Amazon Bedrock embedding model for the knowledge base and user memories. Optional; defaults to `amazon.titan-embed-text-v2:0`. |
| `embedding_dimensions`         | Output dimensions of the embedding model, also used by both vector indexes. Optional; defaults to `1024`. |
| `dynamodb_users_table_name`    | DynamoDB table mapping each Alexa user to a spoken name (plain, non-vector).           |
| `dynamodb_session_memory_table_name` | DynamoDB table holding the short-term chat transcript per session (plain, TTL-expired). |
| `session_memory_ttl_minutes`   | Minutes a session-memory event lives before it expires. Optional; defaults to `5`.     |
| `session_memory_max_messages`  | Most recent session-memory messages replayed to the chat model per request. Optional; defaults to `20`. |
| `dynamodb_user_memory_table_name` | DynamoDB *vector* table storing every user's long-term memories (shared, isolated by ownerId). |
| `dynamodb_user_memory_index_name` | Vector index within the user-memory table. Optional; defaults to `user-memories`.  |
| `alexa_skill_id`               | The Alexa skill ID assigned by the Amazon Developer Console.                           |

#### DynamoDB Setup
No manual steps are required. Terraform provisions the plain **users** and **session-memory** tables, and creates the long-term user-memory **vector** table by running the AWS CLI (the AWS provider has no resource for a DynamoDB vector table), so the AWS CLI must be installed and use the same credentials as Terraform. Set the table names via the `dynamodb_*_table_name` variables in your `terraform.tfvars`.

#### Knowledge Base Setup
Terraform provisions the S3 Vectors bucket and index. To add documents to the knowledge base, upload PDF files to the `ingest/` folder of the knowledge base bucket. An EventBridge rule invokes the Lambda every minute to embed new files into S3 Vectors, then moves each file to `processed/` on success or `failed/` on error.

#### Installation & Deployment
Once configured, deploy everything using:
```sh
./deploy.sh
```

When the deployment completes, note the Lambda ARN in the output values.

## Testing the Assistant

Once the deployment is complete, you can interact with your Alexa skill named **my jarvis**.

![my-jarvis-interaction.png](images/my-jarvis-interaction.png)

### 🗣️ Examples of interactions
- "Alexa, tell my jarvis to remember that my favorite programming language is Java."
- "Alexa, ask my jarvis to recall if Java is my favorite programming language."
- "Alexa, tell my jarvis to remember I have a doctor appointment next Monday at 10 AM."
- "Alexa, ask my jarvis to suggest what should I do for my birthday party."

### Teardown
To remove all deployed resources:
```sh
./undeploy.sh
```

## Architecture
![Software Architecture](./images/software-architecture.png)
This architecture uses an Alexa skill written in Java and hosted as an AWS Lambda function. The Lambda implements a stream handler that processes user requests and responses, using DynamoDB (plain tables for user records and short-term session memory, plus a vector table for long-term user memories) as its backend layer.

![Chat Assistant Service](./assets/chat-assistant-service.png)
As part of the stream handler implementation, it uses a Chat Assistant Service that leverages LangChain4J to manage interactions with the memory stores. This service implements context engineering, ensuring that conversations are enriched with relevant user memories retrieved from DynamoDB and knowledge-base content retrieved from S3 Vectors, re-ranked by Cohere so only the relevant results reach the prompt. Claude Sonnet 5 on Amazon Bedrock is the LLM used to process and generate responses, and Amazon Titan Text Embeddings V2 on Amazon Bedrock generates the embeddings for the knowledge base and user memories.

## Known Issues
- Alexa Developer Console may require manual linking if credentials are not fully synchronized.

## Resources
- [Amazon DynamoDB](https://docs.aws.amazon.com/dynamodb/)
- [Amazon S3 Vectors](https://docs.aws.amazon.com/AmazonS3/latest/userguide/s3-vectors.html)
- [Amazon Bedrock](https://docs.aws.amazon.com/bedrock/)
- [LangChain4J](https://docs.langchain4j.dev)
- [Cohere Rerank](https://docs.cohere.com/docs/rerank)
- [AWS Lambda Documentation](https://docs.aws.amazon.com/lambda)
- [Amazon Alexa Skills Kit](https://developer.amazon.com/en-US/alexa/alexa-skills-kit)

## Maintainers
**Maintainers:**
- Ricardo Ferreira — [@riferrei](https://github.com/riferrei)

## License
This project is licensed under the [MIT License](./LICENSE).
