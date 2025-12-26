package dev.gertjanassies.coffeebar

import dev.gertjanassies.common.CoffeeOrder
import zio.*
import zio.test.*
import zio.test.Assertion.*
import zio.test.TestAspect.*
import zio.http.*
import zio.json.*
import zio.kafka.consumer.*
import zio.kafka.producer.*
import zio.kafka.serde.Serde
import zio.kafka.testkit.{Kafka, KafkaTestUtils}
import dotty.tools.dotc.cc.CheckCaptures.Pre

object OrderEndpointSpec extends ZIOSpecDefault:

  def spec = suite("Order Endpoint with Embedded Kafka")(
    test("POST /order should publish message to order topic") {
      for
        // Create the order request
        request <- ZIO.succeed(
          Request.post(
            URL.decode("/order").toOption.get,
            Body.fromString("""{"name":"TestUser","coffeeType":"TestLatte"}""")
          ).addHeader(Header.ContentType(MediaType.application.json))
        )
        
        // Send the request to the endpoint
        response <- CoffeeBarApp.orderRoute.runZIO(request)
        
        // Verify response is 202 Accepted
        _ <- ZIO.succeed(assertTrue(response.status == Status.Accepted))
        
        // Create a consumer to read from the order topic
        consumer <- KafkaTestUtils.makeConsumer(
          clientId = "test-client",
          groupId = Some("test-group")
        )
        
        // Consume the message from the order topic
        records <- consumer
          .plainStream(Subscription.topics("order"), Serde.string, Serde.string)
          .take(1)
          .runCollect
        
        // Parse the consumed message
        record = records.head.record
        order <- ZIO.fromEither(record.value.fromJson[CoffeeOrder])
        
      yield assertTrue(
        order.name == "TestUser",
        order.coffeeType == "TestLatte",
        order.orderId.nonEmpty
      )
    },
    
    test("GET /check/:orderId should return 200 OK when order is not ready") {
      for
        // Send check request for a non-existent order
        request <- ZIO.succeed(
          Request.get(URL.decode("/check/non-existent-order").toOption.get)
        )
        
        // Send the request to the endpoint
        response <- CoffeeBarApp.orderRoute.runZIO(request)
        
      yield assertTrue(response.status == Status.Ok)
    },
    
    test("GET /check/:orderId should return 303 See Other when order is ready") {
      val testOrderId = "test-check-ready-order"
      val readyOrder = CoffeeOrder("Alice", "Espresso", testOrderId)
      
      for
        // Publish a ready order to the ready topic
        producer <- ZIO.service[Producer]
        _ <- producer.produce(
          new org.apache.kafka.clients.producer.ProducerRecord("ready", readyOrder.orderId, readyOrder.toJson),
          Serde.string,
          Serde.string
        )
        
        // Give PreparedConsumer time to consume the ready message
        _ <- ZIO.sleep(2.seconds)
        
        // Send check request
        request <- ZIO.succeed(
          Request.get(URL.decode(s"/check/$testOrderId").toOption.get)
        )
        
        // Send the request to the endpoint
        response <- CoffeeBarApp.orderRoute.runZIO(request)
        
        // Get the Location header
        locationHeader = response.header(Header.Location)
        
      yield assertTrue(
        response.status == Status.SeeOther,
        locationHeader.isDefined,
        locationHeader.get.url.path.toString == s"/pickup/$testOrderId"
      )
    },
    
    test("GET /pickup/:orderId should return 404 when order does not exist") {
      for
        // Send pickup request for non-existent order
        request <- ZIO.succeed(
          Request.get(URL.decode("/pickup/non-existent-order").toOption.get)
        )
        
        // Send the request to the endpoint
        response <- CoffeeBarApp.orderRoute.runZIO(request)
        
      yield assertTrue(response.status == Status.NotFound)
    },
    
    test("GET /pickup/:orderId should return the order when it exists and remove it") {
      val testOrderId = "test-pickup-order"
      val readyOrder = CoffeeOrder("Bob", "Mocha", testOrderId)
      
      for
        // Publish a ready order to the ready topic
        producer <- ZIO.service[Producer]
        _ <- producer.produce(
          new org.apache.kafka.clients.producer.ProducerRecord("ready", readyOrder.orderId, readyOrder.toJson),
          Serde.string,
          Serde.string
        )
        
        // Give PreparedConsumer time to consume the ready message
        _ <- ZIO.sleep(2.seconds)
        
        // Send pickup request
        request <- ZIO.succeed(
          Request.get(URL.decode(s"/pickup/$testOrderId").toOption.get)
        )
        
        // Send the request to the endpoint
        response <- CoffeeBarApp.orderRoute.runZIO(request)
        
        // Parse response body
        bodyString <- response.body.asString
        retrievedOrder <- ZIO.fromEither(bodyString.fromJson[CoffeeOrder])
        
        // Try to pickup the same order again (should be 404 now)
        secondRequest <- ZIO.succeed(
          Request.get(URL.decode(s"/pickup/$testOrderId").toOption.get)
        )
        secondResponse <- CoffeeBarApp.orderRoute.runZIO(secondRequest)
        
      yield assertTrue(
        response.status == Status.Ok,
        retrievedOrder.name == "Bob",
        retrievedOrder.coffeeType == "Mocha",
        retrievedOrder.orderId == testOrderId,
        secondResponse.status == Status.NotFound // Order should be removed after first pickup
      )
    },
    
    test("Full workflow: order -> check (not ready) -> ready -> check (redirect) -> pickup") {
      for
        // Step 1: Place an order
        orderRequest <- ZIO.succeed(
          Request.post(
            URL.decode("/order").toOption.get,
            Body.fromString("""{"name":"Carol","coffeeType":"Latte"}""")
          ).addHeader(Header.ContentType(MediaType.application.json))
        )
        orderResponse <- CoffeeBarApp.orderRoute.runZIO(orderRequest)
        orderBody <- orderResponse.body.asString
        orderResult <- ZIO.fromEither(orderBody.fromJson[Map[String, String]])
        orderId = orderResult("orderId")
        
        // Step 2: Check order status (should not be ready yet)
        checkRequest1 <- ZIO.succeed(
          Request.get(URL.decode(s"/check/$orderId").toOption.get)
        )
        checkResponse1 <- CoffeeBarApp.orderRoute.runZIO(checkRequest1)
        
        // Step 3: Simulate barista completing the order
        completedOrder = CoffeeOrder("Carol", "Latte", orderId)
        producer <- ZIO.service[Producer]
        _ <- producer.produce(
          new org.apache.kafka.clients.producer.ProducerRecord("ready", completedOrder.orderId, completedOrder.toJson),
          Serde.string,
          Serde.string
        )
        
        // Give PreparedConsumer time to consume the ready message
        _ <- ZIO.sleep(2.seconds)
        
        // Step 4: Check order status (should be ready now with redirect)
        checkRequest2 <- ZIO.succeed(
          Request.get(URL.decode(s"/check/$orderId").toOption.get)
        )
        checkResponse2 <- CoffeeBarApp.orderRoute.runZIO(checkRequest2)
        
        // Step 5: Pickup the order
        pickupRequest <- ZIO.succeed(
          Request.get(URL.decode(s"/check/$orderId").toOption.get)
        )
        pickupResponse <- CoffeeBarApp.orderRoute.runZIO(pickupRequest)
        
      yield assertTrue(
        orderResponse.status == Status.Accepted,
        checkResponse1.status == Status.Ok, // Not ready yet
        checkResponse2.status == Status.SeeOther, // Ready, redirect to pickup
        pickupResponse.status == Status.SeeOther // Still ready
      )
    }
  ).provideSomeShared[Scope](
    Kafka.embedded,
    KafkaTestUtils.producer,
    KafkaTestUtils.consumer(clientId = "coffeebar-test", groupId = Some("coffeebar-test-group")),
    OrderProducer.live,
    PreparedConsumer.live
  ) @@ withLiveClock @@ timeout(2.minutes) @@ sequential
