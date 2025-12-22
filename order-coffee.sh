#!/bin/bash

# Check if required arguments are provided
if [ "$#" -ne 2 ]; then
    echo "Usage: $0 <name> <coffee-type>"
    echo "Example: $0 Alice Latte"
    exit 1
fi

NAME="$1"
COFFEE_TYPE="$2"
BASE_URL="http://localhost:8080"

echo "Ordering $COFFEE_TYPE for $NAME..."

# Place the order and capture response with headers
RESPONSE=$(curl -i -s -X POST "$BASE_URL/order" \
  -H "Content-Type: application/json" \
  -d "{\"name\":\"$NAME\",\"coffeeType\":\"$COFFEE_TYPE\"}")

# Extract Location header
CHECK_URL=$(echo "$RESPONSE" | grep -i "^Location:" | sed 's/Location: //i' | tr -d '\r')

if [ -z "$CHECK_URL" ]; then
    echo "ERROR: Failed to get check URL from response"
    echo "$RESPONSE"
    exit 1
fi

# Extract orderId from response body
ORDER_ID=$(echo "$RESPONSE" | tail -n 1 | grep -o '"orderId":"[^"]*"' | cut -d'"' -f4)

echo "Order placed! Order ID: $ORDER_ID"
echo "Checking status at: $CHECK_URL"

# Poll the check endpoint until ready
while true; do
    echo -n "Checking if order is ready... "
    
    CHECK_RESPONSE=$(curl -i -s "$BASE_URL$CHECK_URL")
    STATUS_CODE=$(echo "$CHECK_RESPONSE" | head -n 1 | grep -o '[0-9]\{3\}')
    
    if [ "$STATUS_CODE" = "303" ]; then
        # Order is ready! Get the pickup URL
        PICKUP_URL=$(echo "$CHECK_RESPONSE" | grep -i "^Location:" | sed 's/Location: //i' | tr -d '\r')
        echo "Ready!"
        echo "Picking up from: $PICKUP_URL"
        
        # Pickup the order
        ORDER=$(curl -s "$BASE_URL$PICKUP_URL")
        
        echo ""
        echo "Order complete!"
        echo "$ORDER" | python3 -m json.tool 2>/dev/null || echo "$ORDER"
        break
    elif [ "$STATUS_CODE" = "200" ]; then
        echo "Not ready yet, waiting..."
        sleep 5
    else
        echo "ERROR: Unexpected status code: $STATUS_CODE"
        echo "$CHECK_RESPONSE"
        exit 1
    fi
done
