# ZIO Kafka Barista

This is an example project for playing around with ZIO-Kafka.

## Architecture

This system simulates a coffee shop workflow with multiple stages:

1. **Customer places order** /order API → `order` topic
2. **One of the Baristas takes order and writes name** → `taken` topic 
3. **One of the Baristas prepares coffee** → `prepared` topic 
4. **One of the Baristas announces order ready** → `ready` topic
5. Meanwhile **Customer checks periodically if the order is ready and then picks up order** API /check /pickup


```
┌─────────────┐
│  Customer   │ POST /order
└──────┬──────┘ returns orderId 
       ▼                          
   [order topic]
       ▼                          
┌──────────────┐
│ Barista[x]   │ misspells name   
└──────┬───────┘
       ▼                          
   [taken topic]
       ▼                          
┌──────────────┐
│ Barista[x]   │ prepares coffee  
└──────┬───────┘
       ▼                          
  [prepared topic]
       ▼                          
┌──────────────┐
│ Barista[x]   | announces coffee 
└──────┬───────┘
       ▼                          
   [ready topic]
       ▼                          
┌─────────────┐                   
│  Customer   │ GET /pickup/:orderId
└─────────────┘
```

## Services

### 1. CoffeeBar (HTTP + Kafka)
**Ports:** HTTP `:8080`

HTTP service for placing and picking up orders:
- **POST /order** - Place a coffee order
  - Body: `{"name": "John", "coffeeType": "Cappuccino"}`
  - Returns: `202 Accepted` with `Location: /check/:orderId` header
  - Body: `{"orderId": "uuid", "status": "Order placed"}`
  
- **GET /check/:orderId** - Check if order is ready (polling endpoint)
  - Returns `200 OK` if order is not ready yet
  - Returns `303 See Other` with `Location: /pickup/:orderId` header when ready
  - Non-destructive check (doesn't remove order from queue)
  
- **GET /pickup/:orderId** - Pick up a ready order
  - Returns order details or 404 if not ready
  - Removes order from queue after pickup

### 2. Barista (Kafka Processor)

Multi-stage coffee preparation pipeline:
- **Stage 1**: Takes order from `order` → writes (misspells) name → publishes to `taken`
- **Stage 2**: Prepares coffee from `taken` → publishes to `prepared`
- **Stage 3**: Announces ready from `prepared` → publishes to `ready`

**Scaling:** Uses consumer group `barista-workers` with static membership.

## Technology Stack

- **Scala 3.3.6**
- **ZIO** - Effect system and streams
  - **ZIO HTTP**
  - **ZIO Kafka**
  - **ZIO JSON**
  - **ZIO Logging**
- **Kafka (Confluent 7.5.0)** - Message broker in KRaft mode

## Prerequisites

- **Docker** and **Docker Compose** (for containerized deployment)
- OR **Scala 3.3.6**, **sbt 1.9.8**, and **Kafka** (for local development)

## Running the Services

### With Docker Compose (Recommended)

```bash
# Start all services
docker-compose up --build

# Stop all services
docker-compose down
```

### Running Locally with SBT

Start Kafka first, then run services in separate terminals:

```bash
# Terminal 1: CoffeeBar (HTTP server on port 8080)
sbt "coffeeBar/run"

# Terminal 2-4: Barista instances (set BARISTA_ID)
BARISTA_ID=1 sbt "barista/run"
BARISTA_ID=2 sbt "barista/run"
..
BARISTA_ID=n sbt "barista/run"

```

## Usage Examples

### Place an Order

```bash
curl -i -X POST http://localhost:8080/order \
  -H "Content-Type: application/json" \
  -d '{"name":"Alice","coffeeType":"Latte"}'

# Response:
# HTTP/1.1 202 Accepted
# Location: /check/550e8400-e29b-41d4-a716-446655440000
# 
# {"orderId":"550e8400-e29b-41d4-a716-446655440000","status":"Order placed"}
```

### Check Order Status

```bash
# Poll to check if order is ready
curl -i http://localhost:8080/check/550e8400-e29b-41d4-a716-446655440000

# Response (if not ready):
# HTTP/1.1 200 OK

# Response (if ready):
# HTTP/1.1 303 See Other
# Location: /pickup/550e8400-e29b-41d4-a716-446655440000
```

### Pick Up Order

```bash
# Pick up the order (following the redirect or directly)
curl http://localhost:8080/pickup/550e8400-e29b-41d4-a716-446655440000

# Response (if ready):
# HTTP/1.1 200 OK
# {"name":"alizze","coffeeType":"Latte","orderId":"550e8400-e29b-41d4-a716-446655440000"}

# Response (if not ready or wrong orderId):
# 404 Not Found
```

## Monitoring

### Kafka UI
Access at `http://localhost:8090`

### Logs
Logs are written to both console and files in `./logs/`:
- `coffeebar.log` - CoffeeBar HTTP and consumer logs
- `barista-1.log` - Barista instance 1 logs
- `barista-2.log` - Barista instance 2 logs  
- `barista-3.log` - Barista instance 3 logs

## Configuration

### Kafka Consumer Groups

- **barista-workers** - Barista processing pipeline (x members with static membership)
- **coffee-bar-pickup** - CoffeeBar ready topic consumer (1 member)

### Environment Variables

| Variable                    | Default          | Description                |
|-----------------------------|------------------|----------------------------|
| `KAFKA_BOOTSTRAP_SERVERS`   | `localhost:9092` | Kafka broker address       |
| `BARISTA_ID`                | `local`          | Barista instance identifier|


## Development

### Build

```bash
# Compile all projects
sbt compile

# Run tests (if any)
sbt test

# Package for Docker
sbt coffeeBar/stage
sbt barista/stage
```

### Clean Build

```bash
sbt clean compile
```

## License


