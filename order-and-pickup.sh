#!/usr/bin/env bash

BASE_URL="http://localhost:8080"

# Fetch coffee menu from the API
echo "Fetching coffee menu..."
menu_response=$(curl -s "$BASE_URL/coffee")
mapfile -t coffees < <(echo "$menu_response" | jq -r '.[].name')

if [ ${#coffees[@]} -eq 0 ]; then
  echo "Failed to fetch coffee menu. Is the server running?"
  exit 1
fi

echo "Available coffees: ${coffees[*]}"
echo ""

# Fixed list of customer names
customers=("Alice" "Bob" "Charlie" "Diana" "Eve" "Frank" "Grace" "Henry")

echo "Coffee Bar Order System"
echo "=============================="
echo ""

# Place one order per coffee from the menu, cycling through customers if needed
declare -a order_ids
declare -a order_names
declare -a order_coffees

for i in "${!coffees[@]}"; do
  coffee="${coffees[$i]}"
  customer="${customers[$i % ${#customers[@]}]}"

  echo "Ordering $coffee for $customer..."

  response=$(curl -s -X POST "$BASE_URL/order" \
    -H "Content-Type: application/json" \
    -d "{\"name\":\"$customer\",\"coffeeType\":\"$coffee\"}")

  order_id=$(echo "$response" | jq -r '.orderId')

  if [ "$order_id" != "null" ] && [ -n "$order_id" ]; then
    order_ids+=("$order_id")
    order_names+=("$customer")
    order_coffees+=("$coffee")
    echo "  Order placed: $order_id"
  else
    echo "  Failed to place order for $coffee ($customer)"
  fi
done

echo ""
echo "=============================="
echo "Total orders placed: ${#order_ids[@]}"
echo ""
echo "Checking and picking up orders in parallel..."
echo "=============================="
echo ""

# Function that polls /check until ready, then picks up
check_and_pickup() {
  local order_id="$1"
  local customer="$2"
  local coffee="$3"

  while true; do
    check_response=$(curl -i -s "$BASE_URL/check/$order_id")
    status_code=$(echo "$check_response" | head -n 1 | grep -o '[0-9]\{3\}')

    if [ "$status_code" = "303" ]; then
      pickup_url=$(echo "$check_response" | grep -i "^Location:" | sed 's/Location: //i' | tr -d '\r')
      pickup_response=$(curl -s "$BASE_URL$pickup_url")
      served_name=$(echo "$pickup_response" | jq -r '.name')
      served_coffee=$(echo "$pickup_response" | jq -r '.coffeeType')
      echo "  Picked up: $served_coffee for $served_name (order: $order_id)"
      break
    elif [ "$status_code" = "200" ]; then
      sleep 2
    else
      echo "  Unexpected status $status_code for order $order_id — giving up"
      break
    fi
  done
}

# Launch a background job for each order
for i in "${!order_ids[@]}"; do
  check_and_pickup "${order_ids[$i]}" "${order_names[$i]}" "${order_coffees[$i]}" &
done

# Wait for all background jobs to finish
wait

echo ""
echo "=============================="
echo "All orders processed!"
