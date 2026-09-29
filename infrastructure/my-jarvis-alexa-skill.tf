resource "null_resource" "my_jarvis_alexa_skill_handler_build" {
  triggers = {
    always_run = timestamp()
  }

  provisioner "local-exec" {
    command     = "mvn clean package"
    interpreter = ["bash", "-c"]
    working_dir = "../lambda"
  }
}

data "local_file" "my_jarvis_skill_handler_jar_file" {
  depends_on = [null_resource.my_jarvis_alexa_skill_handler_build]
  filename   = "../lambda/target/my-jarvis-alexa-skill-1.0.jar"
}

resource "aws_s3_bucket" "my_jarvis_alexa_skill_handler_lambda_artifacts" {
  bucket = "${var.application_prefix}-lambda-artifacts"
}

resource "aws_s3_object" "my_jarvis_skill_handler_lambda_jar" {
  bucket = aws_s3_bucket.my_jarvis_alexa_skill_handler_lambda_artifacts.id
  key    = "functions/${var.application_prefix}/1.0/function.jar"
  source = data.local_file.my_jarvis_skill_handler_jar_file.filename
  etag   = data.local_file.my_jarvis_skill_handler_jar_file.content_md5
}

data "aws_s3_bucket" "existing_knowledge_base" {
  count  = var.create_knowledge_base_bucket ? 0 : 1
  bucket = var.knowledge_base_bucket_name
}

resource "aws_s3_bucket" "my_jarvis_alexa_skill_handler_knowledge_base" {
  count  = var.create_knowledge_base_bucket ? 1 : 0
  bucket = var.knowledge_base_bucket_name
}

locals {
  knowledge_base_bucket_id   = var.create_knowledge_base_bucket ? aws_s3_bucket.my_jarvis_alexa_skill_handler_knowledge_base[0].id : data.aws_s3_bucket.existing_knowledge_base[0].id
  knowledge_base_bucket_name = var.create_knowledge_base_bucket ? aws_s3_bucket.my_jarvis_alexa_skill_handler_knowledge_base[0].bucket : data.aws_s3_bucket.existing_knowledge_base[0].bucket
  knowledge_base_bucket_arn  = var.create_knowledge_base_bucket ? aws_s3_bucket.my_jarvis_alexa_skill_handler_knowledge_base[0].arn : data.aws_s3_bucket.existing_knowledge_base[0].arn
}

resource "aws_s3_object" "my_jarvis_alexa_skill_handler_knowledge_base_ingest_folder" {
  bucket = local.knowledge_base_bucket_id
  key    = "ingest/"
}

resource "aws_s3_object" "my_jarvis_alexa_skill_handler_knowledge_base_processed_folder" {
  bucket = local.knowledge_base_bucket_id
  key    = "processed/"
}

resource "aws_s3_object" "my_jarvis_alexa_skill_handler_knowledge_base_failed_folder" {
  bucket = local.knowledge_base_bucket_id
  key    = "failed/"
}

resource "aws_s3vectors_vector_bucket" "my_jarvis_alexa_skill_handler_knowledge_base_vectors" {
  vector_bucket_name = var.s3_vectors_bucket_name
}

resource "aws_s3vectors_index" "my_jarvis_alexa_skill_handler_knowledge_base_index" {
  index_name         = var.s3_vectors_index_name
  vector_bucket_name = aws_s3vectors_vector_bucket.my_jarvis_alexa_skill_handler_knowledge_base_vectors.vector_bucket_name

  data_type       = "float32"
  dimension       = var.embedding_dimensions
  distance_metric = "cosine"
}

resource "aws_dynamodb_table" "my_jarvis_alexa_skill_handler_users" {
  name         = var.dynamodb_users_table_name
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "userId"

  attribute {
    name = "userId"
    type = "S"
  }
}

resource "aws_dynamodb_table" "my_jarvis_alexa_skill_handler_session_memory" {
  name         = var.dynamodb_session_memory_table_name
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "sessionId"
  range_key    = "eventId"

  attribute {
    name = "sessionId"
    type = "S"
  }

  attribute {
    name = "eventId"
    type = "S"
  }

  ttl {
    attribute_name = "expiresAt"
    enabled        = true
  }
}

data "aws_caller_identity" "current" {}

data "aws_region" "current" {}

locals {
  user_memory_vector_indexes = jsonencode([{
    IndexName        = var.dynamodb_user_memory_index_name
    VectorAttribute  = { AttributeName = "embedding" }
    Dimensions       = var.embedding_dimensions
    DistanceFunction = "COSINE"
    Projection       = { ProjectionType = "ALL" }
    SearchSchema = [
      { AttributeName = "ownerId", SearchSchemaElementType = "INLINE_FILTER" },
      { AttributeName = "subject", SearchSchemaElementType = "INLINE_FILTER" }
    ]
  }])
}

resource "null_resource" "my_jarvis_alexa_skill_handler_user_memories" {
  triggers = {
    region         = data.aws_region.current.region
    table_name     = var.dynamodb_user_memory_table_name
    vector_indexes = local.user_memory_vector_indexes
    stream_view    = "NEW_IMAGE"
  }

  provisioner "local-exec" {
    interpreter = ["bash", "-c"]
    command     = <<-EOT
      set -euo pipefail
      aws dynamodb create-table --region "$REGION" --table-name "$TABLE_NAME" \
        --billing-mode PAY_PER_REQUEST \
        --attribute-definitions AttributeName=id,AttributeType=S AttributeName=ownerId,AttributeType=S \
          AttributeName=subject,AttributeType=S \
        --key-schema AttributeName=id,KeyType=HASH \
        --stream-specification StreamEnabled=true,StreamViewType="$STREAM_VIEW" \
        --vector-indexes "$VECTOR_INDEXES" > /dev/null
      aws dynamodb wait table-exists --region "$REGION" --table-name "$TABLE_NAME"
    EOT
    environment = {
      REGION         = self.triggers.region
      TABLE_NAME     = self.triggers.table_name
      VECTOR_INDEXES = self.triggers.vector_indexes
      STREAM_VIEW    = self.triggers.stream_view
    }
  }

  provisioner "local-exec" {
    when        = destroy
    interpreter = ["bash", "-c"]
    command     = <<-EOT
      set -euo pipefail
      aws dynamodb delete-table --region "$REGION" --table-name "$TABLE_NAME" > /dev/null
      aws dynamodb wait table-not-exists --region "$REGION" --table-name "$TABLE_NAME"
    EOT
    environment = {
      REGION     = self.triggers.region
      TABLE_NAME = self.triggers.table_name
    }
  }
}

resource "null_resource" "my_jarvis_alexa_skill_handler_user_memories_ttl" {
  depends_on = [null_resource.my_jarvis_alexa_skill_handler_user_memories]
  triggers = {
    region     = data.aws_region.current.region
    table_name = var.dynamodb_user_memory_table_name
    table_id   = null_resource.my_jarvis_alexa_skill_handler_user_memories.id
  }

  provisioner "local-exec" {
    interpreter = ["bash", "-c"]
    command     = <<-EOT
      set -euo pipefail
      STATUS=$(aws dynamodb describe-time-to-live --region "$REGION" --table-name "$TABLE_NAME" \
        --query 'TimeToLiveDescription.TimeToLiveStatus' --output text)
      if [ "$STATUS" != "ENABLED" ] && [ "$STATUS" != "ENABLING" ]; then
        aws dynamodb update-time-to-live --region "$REGION" --table-name "$TABLE_NAME" \
          --time-to-live-specification Enabled=true,AttributeName=expiresAt > /dev/null
      fi
    EOT
    environment = {
      REGION     = self.triggers.region
      TABLE_NAME = self.triggers.table_name
    }
  }
}

locals {
  bedrock_chat_model_name            = trimprefix(var.bedrock_chat_model_id, "global.")
  bedrock_chat_inference_profile_arn = "arn:aws:bedrock:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:inference-profile/${var.bedrock_chat_model_id}"
  bedrock_rerank_region              = coalesce(var.bedrock_rerank_region, data.aws_region.current.region)

  bedrock_compression_model_name            = trimprefix(var.bedrock_compression_model_id, "global.")
  bedrock_compression_inference_profile_arn = "arn:aws:bedrock:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:inference-profile/${var.bedrock_compression_model_id}"
}

locals {
  bedrock_chat_and_embedding_statements = [
    {
      Effect   = "Allow"
      Action   = "bedrock:InvokeModel"
      Resource = local.bedrock_chat_inference_profile_arn
      Condition = {
        StringEquals = {
          "aws:RequestedRegion" = data.aws_region.current.region
        }
      }
    },
    {
      Effect   = "Allow"
      Action   = "bedrock:InvokeModel"
      Resource = "arn:aws:bedrock:${data.aws_region.current.region}::foundation-model/${local.bedrock_chat_model_name}"
      Condition = {
        StringEquals = {
          "aws:RequestedRegion"         = data.aws_region.current.region
          "bedrock:InferenceProfileArn" = local.bedrock_chat_inference_profile_arn
        }
      }
    },
    {
      Effect   = "Allow"
      Action   = "bedrock:InvokeModel"
      Resource = "arn:aws:bedrock:::foundation-model/${local.bedrock_chat_model_name}"
      Condition = {
        StringEquals = {
          "aws:RequestedRegion"         = "unspecified"
          "bedrock:InferenceProfileArn" = local.bedrock_chat_inference_profile_arn
        }
      }
    },
    {
      Effect   = "Allow"
      Action   = "bedrock:InvokeModel"
      Resource = "arn:aws:bedrock:${data.aws_region.current.region}::foundation-model/${var.embedding_model_name}"
    }
  ]
}

locals {
  bedrock_compression_statements = [
    {
      Effect   = "Allow"
      Action   = "bedrock:InvokeModel"
      Resource = local.bedrock_compression_inference_profile_arn
      Condition = {
        StringEquals = {
          "aws:RequestedRegion" = data.aws_region.current.region
        }
      }
    },
    {
      Effect   = "Allow"
      Action   = "bedrock:InvokeModel"
      Resource = "arn:aws:bedrock:${data.aws_region.current.region}::foundation-model/${local.bedrock_compression_model_name}"
      Condition = {
        StringEquals = {
          "aws:RequestedRegion"         = data.aws_region.current.region
          "bedrock:InferenceProfileArn" = local.bedrock_compression_inference_profile_arn
        }
      }
    },
    {
      Effect   = "Allow"
      Action   = "bedrock:InvokeModel"
      Resource = "arn:aws:bedrock:::foundation-model/${local.bedrock_compression_model_name}"
      Condition = {
        StringEquals = {
          "aws:RequestedRegion"         = "unspecified"
          "bedrock:InferenceProfileArn" = local.bedrock_compression_inference_profile_arn
        }
      }
    }
  ]
}

resource "aws_iam_role" "my_jarvis_alexa_skill_handler_role" {
  name               = "${var.application_prefix}-role"
  assume_role_policy = <<EOF
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Action": "sts:AssumeRole",
      "Principal": {
        "Service": "lambda.amazonaws.com"
      },
      "Effect": "Allow"
    }
  ]
}
EOF
}

resource "aws_iam_role_policy" "my_jarvis_alexa_skill_handler_role_policy" {
  role = aws_iam_role.my_jarvis_alexa_skill_handler_role.name
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = concat(local.bedrock_chat_and_embedding_statements, local.bedrock_compression_statements, [
      {
        Effect   = "Allow"
        Action   = "bedrock:InvokeModel"
        Resource = "arn:aws:bedrock:${local.bedrock_rerank_region}::foundation-model/${var.bedrock_rerank_model_id}"
      },
      {
        Effect   = "Allow"
        Action   = "bedrock:Rerank"
        Resource = "*"
        Condition = {
          StringEquals = {
            "aws:RequestedRegion" = local.bedrock_rerank_region
          }
        }
      },
      {
        Effect   = "Allow"
        Resource = ["*"]
        Action = [
          "logs:CreateLogGroup",
          "logs:CreateLogStream",
          "logs:PutLogEvents"
        ]
      },
      {
        Effect = "Allow"
        Action = [
          "ec2:CreateNetworkInterface",
          "ec2:DescribeDhcpOptions",
          "ec2:DescribeNetworkInterfaces",
          "ec2:DeleteNetworkInterface",
          "ec2:DescribeSubnets",
          "ec2:DescribeSecurityGroups",
          "ec2:DescribeVpcs"
        ]
        Resource = "*"
      },
      {
        Effect = "Allow"
        Action = [
          "s3:GetObject",
          "s3:ListBucket",
          "s3:DeleteObject",
          "s3:PutObject"
        ]
        Resource = [
          "arn:aws:s3:::${local.knowledge_base_bucket_name}/*",
          "arn:aws:s3:::${local.knowledge_base_bucket_name}"
        ]
      },
      {
        Effect = "Allow"
        Action = [
          "s3vectors:GetVectorBucket",
          "s3vectors:GetIndex",
          "s3vectors:PutVectors",
          "s3vectors:GetVectors",
          "s3vectors:QueryVectors",
          "s3vectors:ListVectors",
          "s3vectors:DeleteVectors"
        ]
        Resource = [
          aws_s3vectors_vector_bucket.my_jarvis_alexa_skill_handler_knowledge_base_vectors.vector_bucket_arn,
          aws_s3vectors_index.my_jarvis_alexa_skill_handler_knowledge_base_index.index_arn
        ]
      },
      {
        Effect = "Allow"
        Action = [
          "dynamodb:BatchWriteItem",
          "dynamodb:Scan",
          "dynamodb:SearchVectors"
        ]
        Resource = [
          "arn:aws:dynamodb:*:*:table/${var.dynamodb_user_memory_table_name}",
          "arn:aws:dynamodb:*:*:table/${var.dynamodb_user_memory_table_name}/index/*"
        ]
      },
      {
        Effect = "Allow"
        Action = [
          "dynamodb:GetItem",
          "dynamodb:PutItem"
        ]
        Resource = [
          aws_dynamodb_table.my_jarvis_alexa_skill_handler_users.arn
        ]
      },
      {
        Effect = "Allow"
        Action = [
          "dynamodb:Query",
          "dynamodb:PutItem",
          "dynamodb:BatchWriteItem",
          "dynamodb:DeleteItem"
        ]
        Resource = [
          aws_dynamodb_table.my_jarvis_alexa_skill_handler_session_memory.arn
        ]
      }
    ])
  })
}

resource "aws_lambda_function" "my_jarvis_alexa_skill_handler" {
  depends_on = [
    null_resource.my_jarvis_alexa_skill_handler_build,
    aws_iam_role.my_jarvis_alexa_skill_handler_role,
    aws_s3_object.my_jarvis_skill_handler_lambda_jar,
    aws_s3vectors_index.my_jarvis_alexa_skill_handler_knowledge_base_index,
    aws_dynamodb_table.my_jarvis_alexa_skill_handler_users,
    aws_dynamodb_table.my_jarvis_alexa_skill_handler_session_memory,
    null_resource.my_jarvis_alexa_skill_handler_user_memories
  ]
  function_name    = "${var.application_prefix}-alexa-skill-handler"
  description      = "Backend function for the My Jarvis Alexa Skill"
  s3_bucket        = aws_s3_bucket.my_jarvis_alexa_skill_handler_lambda_artifacts.id
  s3_key           = aws_s3_object.my_jarvis_skill_handler_lambda_jar.key
  source_code_hash = data.local_file.my_jarvis_skill_handler_jar_file.content_base64sha256
  handler          = "com.riferrei.myjarvis.MyJarvisStreamHandler::handleRequest"
  role             = aws_iam_role.my_jarvis_alexa_skill_handler_role.arn
  runtime          = "java21"
  architectures    = ["arm64"]
  memory_size      = 2048
  timeout          = 60
  environment {
    variables = {
      BEDROCK_CHAT_MODEL_ID        = var.bedrock_chat_model_id
      BEDROCK_CHAT_MAX_TOKENS      = var.bedrock_chat_max_tokens
      BEDROCK_COMPRESSION_MODEL_ID = var.bedrock_compression_model_id
      BEDROCK_RERANK_MODEL_ID      = var.bedrock_rerank_model_id
      BEDROCK_RERANK_REGION        = local.bedrock_rerank_region
      KNOWLEDGE_BASE_BUCKET_NAME   = local.knowledge_base_bucket_name
      S3_VECTORS_BUCKET_NAME       = aws_s3vectors_vector_bucket.my_jarvis_alexa_skill_handler_knowledge_base_vectors.vector_bucket_name
      S3_VECTORS_INDEX_NAME        = aws_s3vectors_index.my_jarvis_alexa_skill_handler_knowledge_base_index.index_name
      EMBEDDING_MODEL_NAME         = var.embedding_model_name
      EMBEDDING_DIMENSIONS         = var.embedding_dimensions

      DYNAMODB_USER_MEMORY_TABLE_NAME = var.dynamodb_user_memory_table_name
      DYNAMODB_USER_MEMORY_INDEX_NAME = var.dynamodb_user_memory_index_name

      DYNAMODB_USERS_TABLE_NAME          = aws_dynamodb_table.my_jarvis_alexa_skill_handler_users.name
      DYNAMODB_SESSION_MEMORY_TABLE_NAME = aws_dynamodb_table.my_jarvis_alexa_skill_handler_session_memory.name
      SESSION_MEMORY_TTL_MINUTES         = var.session_memory_ttl_minutes
      SESSION_MEMORY_MAX_MESSAGES        = var.session_memory_max_messages
    }
  }
}

resource "aws_lambda_permission" "my_jarvis_alexa_skill_handler_alexa_trigger" {
  statement_id       = "AllowExecutionFromAlexa"
  action             = "lambda:InvokeFunction"
  function_name      = aws_lambda_function.my_jarvis_alexa_skill_handler.function_name
  principal          = "alexa-appkit.amazon.com"
  event_source_token = var.alexa_skill_id != "" ? var.alexa_skill_id : null
}

resource "aws_lambda_permission" "my_jarvis_alexa_skill_handler_cloudwatch_trigger" {
  statement_id  = "AllowExecutionFromCloudWatch"
  action        = "lambda:InvokeFunction"
  principal     = "events.amazonaws.com"
  function_name = aws_lambda_function.my_jarvis_alexa_skill_handler.function_name
  source_arn    = aws_cloudwatch_event_rule.my_jarvis_alexa_skill_handler_knowledge_base.arn
}

resource "aws_cloudwatch_event_rule" "my_jarvis_alexa_skill_handler_knowledge_base" {
  name                = "${var.application_prefix}-knowledge-update"
  description         = "Update My Jarvis knowledge base continuously"
  schedule_expression = "rate(1 minute)"
}

resource "aws_cloudwatch_event_target" "my_jarvis_alexa_skill_handler_knowledge_base" {
  rule      = aws_cloudwatch_event_rule.my_jarvis_alexa_skill_handler_knowledge_base.name
  target_id = aws_lambda_function.my_jarvis_alexa_skill_handler.function_name
  arn       = aws_lambda_function.my_jarvis_alexa_skill_handler.arn
  input     = templatefile("templates/knowledge-base-call.tftpl", {})
}

data "aws_dynamodb_table" "my_jarvis_alexa_skill_handler_user_memories" {
  depends_on = [null_resource.my_jarvis_alexa_skill_handler_user_memories]
  name       = var.dynamodb_user_memory_table_name
}

resource "aws_iam_role" "my_jarvis_memory_consolidation_role" {
  name = "${var.application_prefix}-memory-consolidation-role"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Action    = "sts:AssumeRole"
        Principal = { Service = "lambda.amazonaws.com" }
        Effect    = "Allow"
      }
    ]
  })
}

resource "aws_iam_role_policy" "my_jarvis_memory_consolidation_role_policy" {
  role = aws_iam_role.my_jarvis_memory_consolidation_role.name
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = concat(local.bedrock_chat_and_embedding_statements, [
      {
        Effect   = "Allow"
        Resource = ["*"]
        Action = [
          "logs:CreateLogGroup",
          "logs:CreateLogStream",
          "logs:PutLogEvents"
        ]
      },
      {
        Effect = "Allow"
        Action = [
          "dynamodb:GetItem",
          "dynamodb:DeleteItem",
          "dynamodb:SearchVectors"
        ]
        Resource = [
          data.aws_dynamodb_table.my_jarvis_alexa_skill_handler_user_memories.arn,
          "${data.aws_dynamodb_table.my_jarvis_alexa_skill_handler_user_memories.arn}/index/*"
        ]
      },
      {
        Effect = "Allow"
        Action = [
          "dynamodb:DescribeStream",
          "dynamodb:GetRecords",
          "dynamodb:GetShardIterator"
        ]
        Resource = data.aws_dynamodb_table.my_jarvis_alexa_skill_handler_user_memories.stream_arn
      },
      {
        Effect   = "Allow"
        Action   = "dynamodb:ListStreams"
        Resource = "*"
      }
    ])
  })
}

resource "aws_lambda_function" "my_jarvis_memory_dedup_handler" {
  depends_on = [
    aws_iam_role_policy.my_jarvis_memory_consolidation_role_policy,
    aws_s3_object.my_jarvis_skill_handler_lambda_jar
  ]
  function_name    = "${var.application_prefix}-memory-dedup-handler"
  description      = "Deletes the user memories that newer memories replace"
  s3_bucket        = aws_s3_bucket.my_jarvis_alexa_skill_handler_lambda_artifacts.id
  s3_key           = aws_s3_object.my_jarvis_skill_handler_lambda_jar.key
  source_code_hash = data.local_file.my_jarvis_skill_handler_jar_file.content_base64sha256
  handler          = "com.riferrei.myjarvis.MemoryDeDupHandler::handleRequest"
  role             = aws_iam_role.my_jarvis_memory_consolidation_role.arn
  runtime          = "java21"
  architectures    = ["arm64"]
  memory_size      = 512
  timeout          = 120
  environment {
    variables = {
      BEDROCK_CHAT_MODEL_ID           = var.bedrock_chat_model_id
      BEDROCK_CHAT_MAX_TOKENS         = var.bedrock_chat_max_tokens
      EMBEDDING_MODEL_NAME            = var.embedding_model_name
      EMBEDDING_DIMENSIONS            = var.embedding_dimensions
      DYNAMODB_USER_MEMORY_TABLE_NAME = var.dynamodb_user_memory_table_name
      DYNAMODB_USER_MEMORY_INDEX_NAME = var.dynamodb_user_memory_index_name
    }
  }
}

resource "aws_lambda_event_source_mapping" "my_jarvis_memory_consolidation_trigger" {
  event_source_arn               = data.aws_dynamodb_table.my_jarvis_alexa_skill_handler_user_memories.stream_arn
  function_name                  = aws_lambda_function.my_jarvis_memory_dedup_handler.arn
  starting_position              = "LATEST"
  batch_size                     = 10
  maximum_retry_attempts         = 2
  maximum_record_age_in_seconds  = 3600
  bisect_batch_on_function_error = true
  function_response_types        = ["ReportBatchItemFailures"]

  filter_criteria {
    filter {
      pattern = jsonencode({
        eventName = ["INSERT"]
        dynamodb = {
          NewImage = {
            subject = { S = [{ "anything-but" = ["unknown"] }] }
          }
        }
      })
    }
  }
}

output "my_jarvis_alexa_skill_handler_arn" {
  value = aws_lambda_function.my_jarvis_alexa_skill_handler.arn
}
