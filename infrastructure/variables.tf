variable "application_prefix" {
  description = "Prefix for all resources"
  type        = string
}


variable "bedrock_chat_model_id" {
  description = "Amazon Bedrock global inference profile ID for the chat model"
  type        = string
  default     = "global.anthropic.claude-sonnet-5"

  validation {
    condition     = startswith(var.bedrock_chat_model_id, "global.")
    error_message = "bedrock_chat_model_id must be a global inference profile ID (global.*)."
  }
}

variable "bedrock_chat_max_tokens" {
  description = "Maximum output tokens for the Amazon Bedrock chat model"
  type        = number
  default     = 4096
}

variable "cohere_api_key" {
  description = "Cohere API key"
  type        = string
  sensitive   = true
}

variable "cohere_model_name" {
  description = "Model name for scoring model"
  type        = string
  default     = "rerank-multilingual-v3.0"
}

variable "alexa_skill_id" {
  type = string
}

variable "knowledge_base_bucket_name" {
  description = "S3 bucket for knowledge data files"
  type        = string
}

variable "s3_vectors_bucket_name" {
  description = "S3 Vectors vector bucket that backs the knowledge base embeddings"
  type        = string
}

variable "s3_vectors_index_name" {
  description = "S3 Vectors index name within the vector bucket"
  type        = string
  default     = "knowledge-base"
}

variable "embedding_model_name" {
  description = "Amazon Bedrock embedding model used for the knowledge base and user memories"
  type        = string
  default     = "amazon.titan-embed-text-v2:0"
}

variable "embedding_dimensions" {
  description = "Output dimensions of the embedding model; also sets the S3 Vectors index dimension"
  type        = number
  default     = 1024

  validation {
    condition     = contains([256, 512, 1024], var.embedding_dimensions)
    error_message = "embedding_dimensions must be 256, 512, or 1024 for Titan Text Embeddings V2."
  }
}

variable "dynamodb_user_memory_table_name" {
  description = "DynamoDB vector table that stores every user's long-term memories (shared, isolated by ownerId)"
  type        = string
}

variable "dynamodb_user_memory_index_name" {
  description = "Vector index name within the user-memory DynamoDB table"
  type        = string
  default     = "user-memories"
}

variable "dynamodb_users_table_name" {
  description = "DynamoDB table that maps each Alexa user to a spoken name (plain, non-vector)"
  type        = string
}

variable "dynamodb_session_memory_table_name" {
  description = "DynamoDB table that stores the short-term chat transcript per session (plain, non-vector, TTL-expired)"
  type        = string
}

variable "session_memory_ttl_minutes" {
  description = "Minutes a short-term session-memory event lives before it is treated as expired (and eventually TTL-deleted)"
  type        = number
  default     = 5
}

variable "session_memory_max_messages" {
  description = "Maximum number of most recent session-memory messages replayed to the chat model per request"
  type        = number
  default     = 20
}

variable "create_knowledge_base_bucket" {
  description = "Whether to create a new knowledge base bucket or use an existing one"
  type        = bool
  default     = true
}

resource "random_id" "random_id" {
  byte_length = 4
}
