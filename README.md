# Building my own J.A.R.V.I.S assistant

## Overview
This project demonstrates how to create a [J.A.R.V.I.S](https://en.wikipedia.org/wiki/J.A.R.V.I.S.) assistant deployed as an Alexa skill. Built using Java, LangChain4J, AWS Lambda, and Amazon DynamoDB, it enables Alexa to recall past conversations and deliver contextual, intelligent responses. It showcases how to implement a memory layer for AI assistants, enriching the natural language experience through state persistence and fast retrieval.

## Table of Contents
- [Demo Objectives](#demo-objectives)
- [Setup](#setup)
- [Running the Demo](#running-the-demo)
- [Slide Deck](#slide-deck)
- [Architecture](#architecture)
- [Known Issues](#known-issues)
- [Resources](#resources)
- [Maintainers](#maintainers)
- [License](#license)

## Demo Objectives
- Demonstrate how to implement context engineering patterns with LangChain4J.
- Demonstrate DynamoDB (plain and vector tables) as the persistence layer for user records, short-term session memory, and long-term user memories.
- Automate Alexa skill deployment using Terraform, AWS Lambda, and the ASK CLI.

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
| [AWS account](https://aws.amazon.com/account)            | Required to create Lambda, IAM, and CloudWatch resources.                    |
| [Amazon developer account](https://developer.amazon.com) | This is needed to register, deploy, and test Alexa skills.                   |
| [OpenAI](https://auth.openai.com/create-account)         | LLM that will power the intelligent responses for the skill.                 |
| [Cohere](https://cohere.com)                             | Scoring model used to deduplicate memories from the context.                 |

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
| `application_prefix`           | Prefix used for naming AWS resources (Lambda function, S3 bucket, etc.).               |
| `openai_api_key`               | API key used by the Alexa skill to call the OpenAI LLM.                                |
| `openai_model_name`            | Name of the OpenAI model used to generate responses (e.g., `gpt-4o`).                 |
| `cohere_api_key`               | API key used by the Alexa skill to deduplicate memories via Cohere's scoring model.    |
| `knowledge_base_bucket_name`   | Name of the S3 bucket used to upload knowledge base documents.                         |
| `dynamodb_users_table_name`    | DynamoDB table mapping each Alexa user to a spoken name (plain, non-vector).           |
| `dynamodb_session_memory_table_name` | DynamoDB table holding the short-term chat transcript per session (plain, TTL-expired). |
| `session_memory_ttl_minutes`   | Minutes a session-memory event lives before it expires. Optional; defaults to `5`.     |
| `dynamodb_user_memory_table_name` | DynamoDB *vector* table storing every user's long-term memories (shared, isolated by ownerId). |
| `alexa_skill_id`               | The Alexa skill ID assigned by the Amazon Developer Console.                           |

#### DynamoDB Setup
No manual steps are required. Terraform provisions the plain **users** and **session-memory** tables, and the Lambda self-creates the long-term user-memory **vector** table on first use (the AWS provider has no resource for a DynamoDB vector table). Set the table names via the `dynamodb_*_table_name` variables in your `terraform.tfvars`.

#### Installation & Deployment
Once configured, deploy everything using:
```sh
./deploy.sh
```

When the deployment completes, note the output values including the Lambda ARN and function URL.

## Running the Demo

Once the deployment is complete, you can interact with your Alexa skill named **my jarvis**.

![my-jarvis-interaction.png](images/my-jarvis-interaction.png)

### 🗣️ Examples of interactions
- "Alexa, tell my javis to remember that my favorite programming language is Java."
- "Alexa, ask my jarvis to recall if Java is my favorite programming language."
- "Alexa, tell my jarvis to remember I have a doctor appointment next Monday at 10 AM."
- "Alexa, ask my jarvis to suggest what should I do for my birthday party."

### Teardown
To remove all deployed resources:
```sh
./undeploy.sh
```

## Slide Deck
📑 [Beyond Prompting: Context Engineering for Production-Grade AI](./slides/slides.pdf)    
Covers demo goals, motivations for a memory layer, and architecture overview.

## Architecture
![Software Architecture](./images/software-architecture.png)
This architecture uses an Alexa skill written in Java and hosted as an AWS Lambda function. The Lambda implements a stream handler that processes user requests and responses, using DynamoDB (plain tables for user records and short-term session memory, plus a vector table for long-term user memories) as its backend layer.

![Chat Assistant Service](./assets/chat-assistant-service.png)
As part of the stream handler implementation, it uses a Chat Assistant Service that leverages LangChain4J to manage interactions with the memory stores. This service implements context engineering, ensuring that conversations are enriched with relevant historical data retrieved from DynamoDB. OpenAI is the LLM used to process and generate responses.

## Known Issues
- Alexa Developer Console may require manual linking if credentials are not fully synchronized.

## Resources
- [Amazon DynamoDB](https://docs.aws.amazon.com/dynamodb/)
- [AWS Lambda Documentation](https://docs.aws.amazon.com/lambda)
- [Amazon Alexa Skills Kit](https://developer.amazon.com/en-US/alexa/alexa-skills-kit)

## Maintainers
**Maintainers:**
- Ricardo Ferreira — [@riferrei](https://github.com/riferrei)

## License
This project is licensed under the [MIT License](./LICENSE).
