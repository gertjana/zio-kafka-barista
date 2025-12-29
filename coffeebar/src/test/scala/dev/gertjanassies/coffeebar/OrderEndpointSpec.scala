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
        request <- ZIO.succeed(
          Request.post(
            URL.decode("/order").toOption.get,
            Body.fromString("""{"name":"TestUser","coffeeType":"TestLatte"}""")
          ).addHeader(Header.ContentType(MediaType.application.json))
        )
        
        response <- CoffeeBarApp.orderRoute.runZIO(request)
        
        locationHeader = response.header(Header.Location)

        _ <- ZIO.succeed(assertTrue(response.status == Status.Accepted))
        
        consumer <- KafkaTestUtils.makeConsumer(
          clientId = "test-client",
          groupId = Some("test-group")
        )
        
        records <- consumer
          .plainStream(Subscription.topics("order"), Serde.string, Serde.string)
          .take(1)
          .runCollect
        
        record = records.head.record
        order <- ZIO.fromEither(record.value.fromJson[CoffeeOrder])
        
      yield assertTrue(
        order.name == "TestUser",
        order.coffeeType == "TestLatte",
        order.orderId.nonEmpty,
        locationHeader == Some(Header.Location(URL.decode(s"/check/${order.orderId}").toOption.get))
      )
    },
    
    test("GET /check/:orderId should return 200 OK when order is not ready") {
      for
        request <- ZIO.succeed(
          Request.get(URL.decode("/check/non-existent-order").toOption.get)
        )
        
        response <- CoffeeBarApp.orderRoute.runZIO(request)
        
      yield assertTrue(response.status == Status.Ok)
    },
    
    test("GET /check/:orderId should return 303 See Other when order is ready") {
      val testOrderId = "test-check-ready-order"
      val readyOrder = CoffeeOrder("Alice", "Espresso", testOrderId)
      
      for
        producer <- ZIO.service[Producer]
        _ <- producer.produce(
          new org.apache.kafka.clients.producer.ProducerRecord("ready", readyOrder.orderId, readyOrder.toJson),
          Serde.string,
          Serde.string
        )
        
        _ <- ZIO.sleep(2.seconds)
        
        request <- ZIO.succeed(
          Request.get(URL.decode(s"/check/$testOrderId").toOption.get)
        )
        
        response <- CoffeeBarApp.orderRoute.runZIO(request)
        
        locationHeader = response.header(Header.Location)
        
      yield assertTrue(
        response.status == Status.SeeOther,
        locationHeader.isDefined,
        locationHeader.get.url.path.toString == s"/pickup/$testOrderId"
      )
    },
    
    test("GET /pickup/:orderId should return 404 when order does not exist") {
      for
        request <- ZIO.succeed(
          Request.get(URL.decode("/pickup/non-existent-order").toOption.get)
        )
        
        response <- CoffeeBarApp.orderRoute.runZIO(request)
        
      yield assertTrue(response.status == Status.NotFound)
    },
    
    test("GET /pickup/:orderId should return the order when it exists and remove it") {
      val testOrderId = "test-pickup-order"
      val readyOrder = CoffeeOrder("Bob", "Mocha", testOrderId)
      
      for
        producer <- ZIO.service[Producer]
        _ <- producer.produce(
          new org.apache.kafka.clients.producer.ProducerRecord("ready", readyOrder.orderId, readyOrder.toJson),
          Serde.string,
          Serde.string
        )
        
        _ <- ZIO.sleep(2.seconds)
        
        request <- ZIO.succeed(
          Request.get(URL.decode(s"/pickup/$testOrderId").toOption.get)
        )
        
        response <- CoffeeBarApp.orderRoute.runZIO(request)
        
        bodyString <- response.body.asString
        retrievedOrder <- ZIO.fromEither(bodyString.fromJson[CoffeeOrder])
        
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
        
        checkRequest1 <- ZIO.succeed(
          Request.get(URL.decode(s"/check/$orderId").toOption.get)
        )
        checkResponse1 <- CoffeeBarApp.orderRoute.runZIO(checkRequest1)
        
        completedOrder = CoffeeOrder("Carol", "Latte", orderId)
        producer <- ZIO.service[Producer]
        _ <- producer.produce(
          new org.apache.kafka.clients.producer.ProducerRecord("ready", completedOrder.orderId, completedOrder.toJson),
          Serde.string,
          Serde.string
        )
        
        _ <- ZIO.sleep(2.seconds)
        
        checkRequest2 <- ZIO.succeed(
          Request.get(URL.decode(s"/check/$orderId").toOption.get)
        )
        checkResponse2 <- CoffeeBarApp.orderRoute.runZIO(checkRequest2)
        
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
