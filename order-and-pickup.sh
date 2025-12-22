#!/usr/bin/env bash

# Lists of coffees and customers
coffees=("Espresso" "Cappuccino" "Latte" "Americano" "Mocha")
customers=("Alice" "Bob" "Charlie" "Diana" "Eve")

# Array to store order IDs
declare -a order_ids

echo "🔔 Coffee Bar Order System 🔔"
echo "=============================="
echo ""

# Place orders - match each customer with one coffee
for i in "${!customers[@]}"; do
  customer="${customers[$i]}"
  coffee="${coffees[$i]}"
  
  echo "Ordering $coffee for $customer..."
  
  # Make the order and extract the orderId
  response=$(curl -s -X POST http://localhost:8080/order \
    -H "Content-Type: application/json" \
    -d "{\"name\":\"$customer\",\"coffeeType\":\"$coffee\"}")
  
  # Extract orderId from JSON response using jq
  order_id=$(echo "$response" | jq -r '.orderId')
  
  if [ -n "$order_id" ]; then
    order_ids+=("$order_id")
    echo "✓ Order placed: $order_id"
  else
    echo "✗ Failed to place order"
  fi
  
  # Small delay between orders
  sleep 0.2
done

echo ""
echo "=============================="
echo "Total orders placed: ${#order_ids[@]}"
echo ""
echo "Press any key to pick up all orders..."
read -n 1 -s -r
echo ""
echo "=============================="
echo "Picking up orders..."
echo ""

# Pick up all orders
for order_id in "${order_ids[@]}"; do
  echo "Picking up order: $order_id"
  response=$(curl -s http://localhost:8080/pickup/$order_id)
  
  if echo "$response" | grep -q "orderId"; then
    # Extract name and coffee type for display
    name=$(echo "$response" | jq -r '.name')
    coffee=$(echo "$response" | jq -r '.coffeeType')
    echo "✓ Picked up: $coffee for $name"
  else
    echo "✗ Order not ready or not found: $order_id"
  fi
  
  sleep 0.1
done

echo ""
echo "=============================="
echo "All orders processed!"
