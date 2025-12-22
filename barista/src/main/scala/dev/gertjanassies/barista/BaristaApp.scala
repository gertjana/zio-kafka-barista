package dev.gertjanassies.barista

import dev.gertjanassies.common.CoffeeOrder
import zio.*
import zio.kafka.consumer.*
import zio.kafka.producer.*
import zio.kafka.serde.Serde
import zio.json.*
import zio.logging.backend.SLF4J
import org.apache.kafka.clients.producer.ProducerRecord

object BaristaApp extends ZIOAppDefault:
  private val baristaId = sys.env.getOrElse("BARISTA_ID", "local")

  private def misspell(name: String): String =
    name.toLowerCase()
      .replace("ch", "tj")
      .replace("c", "z")
      .replace("n", "nn")
      .replace("ve", "va")
      .replace("ob", "op")
      .capitalize

  // Process orders from 'order' topic - take and misspell the name
  val processOrder: ZIO[Consumer & Producer, Throwable, Unit] =
    Consumer
      .plainStream(Subscription.topics("order"), Serde.string, Serde.string)
      .mapZIO { record =>
        for
          _ <- Console.printLine(s"[Barista-$baristaId] Taking order: ${record.value}")
          order <- ZIO.fromEither(
            record.value.fromJson[CoffeeOrder]
          ).mapError(err => new RuntimeException(s"Failed to parse order: $err"))
          
          // Misspell the name (simple transformation)
          misspelledName = misspell(order.name)
          takenOrder = order.copy(name = misspelledName)
          
          _ <- Console.printLine(s"[Barista-$baristaId] Taking order and writing name...")
          _ <- ZIO.sleep(3.seconds)
          _ <- Console.printLine(s"[Barista-$baristaId] Took order, wrote name as: $misspelledName")
          
          // Publish to 'taken' topic
          takenRecord = new ProducerRecord[String, String](
            "taken",
            order.orderId,
            takenOrder.toJson
          )
          producer <- ZIO.service[Producer]
          _ <- producer.produce(takenRecord, Serde.string, Serde.string)
        yield ()
      }
      .runDrain

  // Process taken orders - prepare the coffee
  val processTaken: ZIO[Consumer & Producer, Throwable, Unit] =
    Consumer
      .plainStream(Subscription.topics("taken"), Serde.string, Serde.string)
      .mapZIO { record =>
        for
          _ <- Console.printLine(s"[Barista-$baristaId] Preparing taken order: ${record.value}")
          order <- ZIO.fromEither(
            record.value.fromJson[CoffeeOrder]
          ).mapError(err => new RuntimeException(s"Failed to parse order: $err"))
          
          // Simulate coffee preparation
          _ <- Console.printLine(s"[Barista-$baristaId] Preparing ${order.coffeeType} for ${order.name}...")
          _ <- ZIO.sleep(3.seconds)
          _ <- Console.printLine(s"[Barista-$baristaId] ${order.coffeeType} ready for ${order.name}!")
          
          // Publish to 'prepared' topic
          preparedRecord = new ProducerRecord[String, String](
            "prepared",
            order.orderId,
            order.toJson
          )
          producer <- ZIO.service[Producer]
          _ <- producer.produce(preparedRecord, Serde.string, Serde.string)
        yield ()
      }
      .runDrain

  // Process prepared orders - announce they're ready
  val processPrepared: ZIO[Consumer & Producer, Throwable, Unit] =
    Consumer
      .plainStream(Subscription.topics("prepared"), Serde.string, Serde.string)
      .mapZIO { record =>
        for
          _ <- Console.printLine(s"[Barista-$baristaId] Announcing prepared order: ${record.value}")
          order <- ZIO.fromEither(
            record.value.fromJson[CoffeeOrder]
          ).mapError(err => new RuntimeException(s"Failed to parse order: $err"))
          
          // Simulate announcing work
          _ <- Console.printLine(s"[Barista-$baristaId] Calling out name...")
          _ <- ZIO.sleep(3.seconds)
          
          // Announce the order is ready
          _ <- Console.printLine(s"🔔 ${order.name.toUpperCase()}! YOUR ${order.coffeeType.toUpperCase()} IS READY! 🔔")
          
          // Publish to 'ready' topic
          readyRecord = new ProducerRecord[String, String](
            "ready",
            order.orderId,
            order.toJson
          )
          producer <- ZIO.service[Producer]
          _ <- producer.produce(readyRecord, Serde.string, Serde.string)
        yield ()
      }
      .runDrain

  // Process all topics in priority order: prepared > taken > order
  val processOrders: ZIO[Consumer & Producer, Throwable, Unit] =
    ZIO.collectAllParDiscard(List(
      processPrepared,
      processTaken,
      processOrder
    ))

  override def run =
    processOrders.provide(
      Runtime.removeDefaultLoggers >>> SLF4J.slf4j,
      KafkaConfig.consumerLayer,
      KafkaConfig.producerLayer
    )
