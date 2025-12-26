package dev.gertjanassies.barista

import dev.gertjanassies.common.CoffeeOrder
import zio.*
import zio.test.*
import zio.test.Assertion.*
import zio.test.TestAspect.*
import zio.json.*
import zio.kafka.consumer.*
import zio.kafka.producer.*
import zio.kafka.serde.Serde
import zio.kafka.testkit.{Kafka, KafkaTestUtils}
import org.apache.kafka.clients.producer.ProducerRecord

object BaristaStagesSpec extends ZIOSpecDefault:

  def spec = suite("Barista Processing Stages with Embedded Kafka")(
    
    test("processOrder: order -> taken (with name misspelling)") {
      val order = CoffeeOrder("Charles", "Latte", "test-order-1")
      for
        
        // Publish to order topic
        producer <- ZIO.service[Producer]
        _ <- producer.produce(
          new ProducerRecord("order", order.orderId, order.toJson),
          Serde.string,
          Serde.string
        )
        
        // Run the processOrder stage
        processFiber <- BaristaApp.processOrder.fork
        
        // Give it time to process
        _ <- ZIO.sleep(5.seconds)
        
        // Create consumer for taken topic
        consumer <- KafkaTestUtils.makeConsumer(
          clientId = "test-taken-consumer",
          groupId = Some("test-taken-group")
        )
        
        // Consume from taken topic until we find our order
        takenOrder <- consumer
          .plainStream(Subscription.topics("taken"), Serde.string, Serde.string)
          .mapZIO(record => ZIO.fromEither(record.record.value.fromJson[CoffeeOrder]))
          .filter(_.orderId == "test-order-1")
          .take(1)
          .timeout(10.seconds)
          .runHead
          .some
        
        // Interrupt the processor
        _ <- processFiber.interrupt
        
      yield assertTrue(
        takenOrder.orderId == "test-order-1",
        takenOrder.coffeeType == "Latte",
        takenOrder.name != "Charles" // Name should be misspelled
      )
    },
    
    test("processTaken: taken -> prepared") {
      val order = CoffeeOrder("Aliza", "Cappuccino", "test-taken-1")
      
      for
        
        // Publish to taken topic
        producer <- ZIO.service[Producer]
        _ <- producer.produce(
          new ProducerRecord("taken", order.orderId, order.toJson),
          Serde.string,
          Serde.string
        )
        
        // Run the processTaken stage
        processFiber <- BaristaApp.processTaken.fork
        
        // Give it time to process (3 seconds delay + processing time)
        _ <- ZIO.sleep(5.seconds)
        
        // Create consumer for prepared topic
        consumer <- KafkaTestUtils.makeConsumer(
          clientId = "test-prepared-consumer",
          groupId = Some("test-prepared-group")
        )
        
        // Consume from prepared topic until we find our order
        preparedOrder <- consumer
          .plainStream(Subscription.topics("prepared"), Serde.string, Serde.string)
          .mapZIO(record => ZIO.fromEither(record.record.value.fromJson[CoffeeOrder]))
          .filter(_.orderId == "test-taken-1")
          .take(1)
          .timeout(10.seconds)
          .runHead
          .some
        
        // Interrupt the processor
        _ <- processFiber.interrupt
        
      yield assertTrue(
        preparedOrder.orderId == "test-taken-1",
        preparedOrder.coffeeType == "Cappuccino",
        preparedOrder.name == "Aliza" // Name unchanged in this stage
      )
    },
    
    test("processPrepared: prepared -> ready") {
      val order = CoffeeOrder("STAVA", "Mocha", "test-prepared-1")
      
      for
        // Publish to prepared topic
        producer <- ZIO.service[Producer]
        _ <- producer.produce(
          new ProducerRecord("prepared", order.orderId, order.toJson),
          Serde.string,
          Serde.string
        )
        
        // Run the processPrepared stage
        processFiber <- BaristaApp.processPrepared.fork
        
        // Give it time to process (3 seconds delay + processing time)
        _ <- ZIO.sleep(5.seconds)
        
        // Create consumer for ready topic
        consumer <- KafkaTestUtils.makeConsumer(
          clientId = "test-ready-consumer",
          groupId = Some("test-ready-group")
        )
        
        // Consume from ready topic until we find our order
        readyOrder <- consumer
          .plainStream(Subscription.topics("ready"), Serde.string, Serde.string)
          .mapZIO(record => ZIO.fromEither(record.record.value.fromJson[CoffeeOrder]))
          .filter(_.orderId == "test-prepared-1")
          .take(1)
          .timeout(10.seconds)
          .runHead
          .some
        
        // Interrupt the processor
        _ <- processFiber.interrupt
        
      yield assertTrue(
        readyOrder.orderId == "test-prepared-1",
        readyOrder.coffeeType == "Mocha",
        readyOrder.name == "STAVA" // Name unchanged in this stage
      )
    },
    
    test("full pipeline: order -> taken -> prepared -> ready") {
      val order = CoffeeOrder("Bob", "Americano", "test-full-pipeline")
      
      for
        // Publish to order topic
        producer <- ZIO.service[Producer]
        _ <- producer.produce(
          new ProducerRecord("order", order.orderId, order.toJson),
          Serde.string,
          Serde.string
        )
        
        // Run all processors
        processFiber <- BaristaApp.processOrders.fork
        
        // Give it time to go through all stages (3s * 3 stages + overhead)
        _ <- ZIO.sleep(12.seconds)
        
        // Create consumer for ready topic (final output)
        consumer <- KafkaTestUtils.makeConsumer(
          clientId = "test-pipeline-consumer",
          groupId = Some("test-pipeline-group")
        )
        
        // Consume from ready topic until we find our order
        finalOrder <- consumer
          .plainStream(Subscription.topics("ready"), Serde.string, Serde.string)
          .mapZIO(record => ZIO.fromEither(record.record.value.fromJson[CoffeeOrder]))
          .filter(_.orderId == "test-full-pipeline")
          .take(1)
          .timeout(15.seconds)
          .runHead
          .some
        
        // Interrupt the processors
        _ <- processFiber.interrupt
        
      yield assertTrue(
        finalOrder.orderId == "test-full-pipeline",
        finalOrder.coffeeType == "Americano",
        finalOrder.name != "Bob" // Name should be misspelled
      )
    }
    
  ).provideSomeShared[Scope](
    Kafka.embedded,
    KafkaTestUtils.producer,
    KafkaTestUtils.consumer(clientId = "barista-test", groupId = Some("barista-test-group"))
  ) @@ withLiveClock @@ timeout(3.minutes) @@ sequential
