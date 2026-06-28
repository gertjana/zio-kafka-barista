package dev.gertjanassies.coffeebar

import dev.gertjanassies.common.CoffeeOrder
import zio.*
import zio.kafka.consumer.*
import zio.kafka.serde.Serde
import zio.json.*

trait PreparedConsumer:
  def getOrder(orderId: String): Task[Option[CoffeeOrder]]
  def isReady(orderId: String): Task[Boolean]

object PreparedConsumer:
  def getOrder(
      orderId: String
  ): ZIO[PreparedConsumer, Throwable, Option[CoffeeOrder]] =
    ZIO.serviceWithZIO[PreparedConsumer](_.getOrder(orderId))

  def isReady(orderId: String): ZIO[PreparedConsumer, Throwable, Boolean] =
    ZIO.serviceWithZIO[PreparedConsumer](_.isReady(orderId))

  def live: ZLayer[Consumer, Nothing, PreparedConsumer] =
    ZLayer.scoped {
      for
        consumer <- ZIO.service[Consumer]
        orders <- Ref.make(Map.empty[String, CoffeeOrder])
        consumeEffect =
          consumer
            .plainStream(
              Subscription.topics("ready"),
              Serde.string,
              Serde.string
            )
            .mapZIO { record =>
              record.value.fromJson[CoffeeOrder] match
                case Right(order) =>
                  ZIO.logInfo(
                    s"[CoffeeBar] Received ready order: ${order.orderId} for ${order.name}"
                  ) *>
                    orders.update(_ + (order.orderId -> order))
                case Left(error) =>
                  ZIO.logWarning(s"[CoffeeBar] Failed to parse order: $error")
            }
            .runDrain
        _ <- consumeEffect.forkScoped
      yield new PreparedConsumer:
        def getOrder(orderId: String): Task[Option[CoffeeOrder]] =
          for
            result <- orders.modify(m => (m.get(orderId), m - orderId))
            _ <- ZIO.logInfo(s"[CoffeeBar] Pickup request for $orderId: ${
                if (result.isDefined) "Found" else "Not found"
              }")
          yield result

        def isReady(orderId: String): Task[Boolean] =
          for
            ready <- orders.get.map(_.contains(orderId))
            _ <- ZIO.logInfo(s"[CoffeeBar] Ready check for $orderId: ${
                if (ready) "Ready" else "Not ready"
              }")
          yield ready
    }
