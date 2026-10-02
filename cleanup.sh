#!/bin/bash

set -euo pipefail

cd "$(dirname "$0")"

echo "🔍 Looking up the data stores of the deployment..."

LAMBDA_ARN=$(terraform -chdir=infrastructure output -raw my_jarvis_alexa_skill_handler_arn 2>/dev/null || true)

if [ -z "$LAMBDA_ARN" ]; then
    echo "Error: Could not read the Lambda ARN from Terraform. Is the project deployed?"
    exit 1
fi

REGION=$(echo "$LAMBDA_ARN" | cut -d: -f4)

VARIABLES=$(aws lambda get-function-configuration --region "$REGION" --function-name "$LAMBDA_ARN" \
    --query 'Environment.Variables' --output json)

USERS_TABLE=$(jq -r '.DYNAMODB_USERS_TABLE_NAME' <<< "$VARIABLES")
SESSION_MEMORY_TABLE=$(jq -r '.DYNAMODB_SESSION_MEMORY_TABLE_NAME' <<< "$VARIABLES")
USER_MEMORY_TABLE=$(jq -r '.DYNAMODB_USER_MEMORY_TABLE_NAME' <<< "$VARIABLES")
VECTOR_BUCKET=$(jq -r '.S3_VECTORS_BUCKET_NAME' <<< "$VARIABLES")
VECTOR_INDEX=$(jq -r '.S3_VECTORS_INDEX_NAME' <<< "$VARIABLES")
KNOWLEDGE_BASE_BUCKET=$(jq -r '.KNOWLEDGE_BASE_BUCKET_NAME' <<< "$VARIABLES")

SKILL_FUNCTION=$(echo "$LAMBDA_ARN" | cut -d: -f7)
DEDUP_FUNCTION="${SKILL_FUNCTION%-alexa-skill-handler}-memory-dedup-handler"

SKILL_LOG_GROUP=$(aws lambda get-function-configuration --region "$REGION" --function-name "$SKILL_FUNCTION" \
    --query 'LoggingConfig.LogGroup' --output text)
DEDUP_LOG_GROUP=$(aws lambda get-function-configuration --region "$REGION" --function-name "$DEDUP_FUNCTION" \
    --query 'LoggingConfig.LogGroup' --output text)

echo ""
echo "⚠️  This is a destructive action and it can't be undone."
echo "It permanently erases all the data stored by My Jarvis:"
echo "  • every item in the DynamoDB tables $USERS_TABLE, $SESSION_MEMORY_TABLE and $USER_MEMORY_TABLE"
echo "  • every vector in the S3 Vectors index $VECTOR_INDEX of the vector bucket $VECTOR_BUCKET"
echo "  • every document in the S3 bucket $KNOWLEDGE_BASE_BUCKET"
echo "  • every log stream in the CloudWatch log groups $SKILL_LOG_GROUP and $DEDUP_LOG_GROUP"
echo ""
read -r -p "Do you want to continue? [Y/n] " ANSWER || true

if [ "$ANSWER" != "Y" ] && [ "$ANSWER" != "y" ]; then
    echo "❌ Cleanup cancelled. Nothing was erased."
    exit 0
fi

echo "⚙️ Erasing the documents of the knowledge base bucket"

aws s3 rm "s3://$KNOWLEDGE_BASE_BUCKET" --region "$REGION" --recursive --only-show-errors \
    --exclude "ingest/" --exclude "processed/" --exclude "failed/"

echo "⚙️ Erasing the vectors of the knowledge base index"

VECTOR_KEYS=$(aws s3vectors list-vectors --region "$REGION" --vector-bucket-name "$VECTOR_BUCKET" \
    --index-name "$VECTOR_INDEX" --query 'vectors[].key' --output json)
VECTOR_KEYS=${VECTOR_KEYS:-[]}
VECTOR_COUNT=$(jq 'length' <<< "$VECTOR_KEYS")

for ((i = 0; i < VECTOR_COUNT; i += 500)); do
    aws s3vectors delete-vectors --region "$REGION" --vector-bucket-name "$VECTOR_BUCKET" \
        --index-name "$VECTOR_INDEX" --keys "$(jq -c --argjson i "$i" '.[$i:($i + 500)]' <<< "$VECTOR_KEYS")"
done

echo "✅ Erased $VECTOR_COUNT vectors"

erase_table() {
    local table=$1
    local key_names names projection items count request

    key_names=$(aws dynamodb describe-table --region "$REGION" --table-name "$table" \
        --query 'Table.KeySchema[].AttributeName' --output json)
    names=$(jq -c 'to_entries | map({key: "#k\(.key)", value: .value}) | from_entries' <<< "$key_names")
    projection=$(jq -r 'to_entries | map("#k\(.key)") | join(", ")' <<< "$key_names")

    items=$(aws dynamodb scan --region "$REGION" --table-name "$table" \
        --projection-expression "$projection" --expression-attribute-names "$names" \
        --query 'Items' --output json)
    items=${items:-[]}
    count=$(jq 'length' <<< "$items")

    for ((i = 0; i < count; i += 25)); do
        request=$(jq -c --arg table "$table" --argjson i "$i" \
            '{($table): [.[$i:($i + 25)][] | {DeleteRequest: {Key: .}}]}' <<< "$items")
        while true; do
            request=$(aws dynamodb batch-write-item --region "$REGION" --request-items "$request" \
                --query 'UnprocessedItems' --output json)
            if [ -z "$request" ] || [ "$request" = "{}" ] || [ "$request" = "null" ]; then
                break
            fi
            sleep 1
        done
    done

    echo "✅ Erased $count items from $table"
}

echo "⚙️ Erasing the items of the DynamoDB tables"

erase_table "$USERS_TABLE"
erase_table "$SESSION_MEMORY_TABLE"
erase_table "$USER_MEMORY_TABLE"

erase_log_group() {
    local group=$1
    local streams count

    streams=$(aws logs describe-log-streams --region "$REGION" --log-group-name "$group" \
        --query 'logStreams[].logStreamName' --output json)
    streams=${streams:-[]}
    count=$(jq 'length' <<< "$streams")

    while read -r stream; do
        aws logs delete-log-stream --region "$REGION" --log-group-name "$group" --log-stream-name "$stream"
    done < <(jq -r '.[]' <<< "$streams")

    echo "✅ Erased $count log streams from $group"
}

echo "⚙️ Erasing the logs of the Lambda functions"

erase_log_group "$SKILL_LOG_GROUP"
erase_log_group "$DEDUP_LOG_GROUP"

echo "🧹 Cleanup complete. My Jarvis starts from a clean slate on the next request."
