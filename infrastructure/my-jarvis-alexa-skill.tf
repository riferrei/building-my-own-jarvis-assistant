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

  data_type = "float32"
  # 1536 matches OpenAI text-embedding-3-small; keep in sync with embedding_model_name.
  dimension       = 1536
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

resource "aws_iam_role" "my_jarvis_alexa_skill_handler_role" {
  name               = "${var.application_prefix}-_role"
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
    Statement = [
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
        # User-memory DynamoDB vector table. There is no Terraform resource for
        # a DynamoDB vector table, so the Lambda creates it on first use via
        # createTableIfNotExists(true) — hence CreateTable/DescribeTable here in
        # addition to the data-plane actions. Scoped to the table and its
        # indexes only.
        Effect = "Allow"
        Action = [
          "dynamodb:CreateTable",
          "dynamodb:DescribeTable",
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
    ]
  })
}

resource "aws_lambda_function" "my_jarvis_alexa_skill_handler" {
  depends_on = [
    null_resource.my_jarvis_alexa_skill_handler_build,
    aws_iam_role.my_jarvis_alexa_skill_handler_role,
    aws_s3_object.my_jarvis_skill_handler_lambda_jar,
    aws_s3vectors_index.my_jarvis_alexa_skill_handler_knowledge_base_index,
    aws_dynamodb_table.my_jarvis_alexa_skill_handler_users,
    aws_dynamodb_table.my_jarvis_alexa_skill_handler_session_memory
  ]
  function_name    = "${var.application_prefix}-function"
  description      = "Backend function for the My Jarvis Alexa Skill"
  s3_bucket        = aws_s3_bucket.my_jarvis_alexa_skill_handler_lambda_artifacts.id
  s3_key           = aws_s3_object.my_jarvis_skill_handler_lambda_jar.key
  source_code_hash = data.local_file.my_jarvis_skill_handler_jar_file.content_base64sha256
  handler          = "com.riferrei.myjarvis.MyJarvisStreamHandler::handleRequest"
  role             = aws_iam_role.my_jarvis_alexa_skill_handler_role.arn
  runtime          = "java21"
  memory_size      = 512
  timeout          = 60
  environment {
    variables = {
      OPENAI_API_KEY               = var.openai_api_key
      OPENAI_MODEL_NAME            = var.openai_model_name
      OPENAI_CHAT_TEMPERATURE      = var.openai_chat_temperature
      OPENAI_CHAT_MAX_TOKENS       = var.openai_chat_max_tokens
      COHERE_API_KEY               = var.cohere_api_key
      COHERE_MODEL_NAME            = var.cohere_model_name
      REDIS_LANGCACHE_API_BASE_URL = var.langcache_api_base_url
      REDIS_LANGCACHE_API_KEY      = var.langcache_api_key
      REDIS_LANGCACHE_CACHE_ID     = var.langcache_cache_id
      KNOWLEDGE_BASE_BUCKET_NAME   = local.knowledge_base_bucket_name
      S3_VECTORS_BUCKET_NAME       = aws_s3vectors_vector_bucket.my_jarvis_alexa_skill_handler_knowledge_base_vectors.vector_bucket_name
      S3_VECTORS_INDEX_NAME        = aws_s3vectors_index.my_jarvis_alexa_skill_handler_knowledge_base_index.index_name
      EMBEDDING_MODEL_NAME         = var.embedding_model_name

      DYNAMODB_USER_MEMORY_TABLE_NAME = var.dynamodb_user_memory_table_name
      DYNAMODB_USER_MEMORY_INDEX_NAME = var.dynamodb_user_memory_index_name

      DYNAMODB_USERS_TABLE_NAME          = aws_dynamodb_table.my_jarvis_alexa_skill_handler_users.name
      DYNAMODB_SESSION_MEMORY_TABLE_NAME = aws_dynamodb_table.my_jarvis_alexa_skill_handler_session_memory.name
      SESSION_MEMORY_TTL_MINUTES         = var.session_memory_ttl_minutes
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

output "my_jarvis_alexa_skill_handler_arn" {
  value = aws_lambda_function.my_jarvis_alexa_skill_handler.arn
}
