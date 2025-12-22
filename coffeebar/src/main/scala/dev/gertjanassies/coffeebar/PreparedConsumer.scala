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
  def getOrder(orderId: String): ZIO[PreparedConsumer, Throwable, Option[CoffeeOrder]] =
    ZIO.serviceWithZIO[PreparedConsumer](_.getOrder(orderId))
  
  def isReady(orderId: String): ZIO[PreparedConsumer, Throwable, Boolean] =
    ZIO.serviceWithZIO[PreparedConsumer](_.isReady(orderId))

  def live: ZLayer[Consumer, Nothing, PreparedConsumer] =
    ZLayer.fromFunction { (consumer: Consumer) =>
      new PreparedConsumer:
        private val orders = scala.collection.mutable.Map[String, CoffeeOrder]()
        
        // Background fiber to consume from 'ready' topic
        private val consumeEffect: Task[Unit] =
          consumer
            .plainStream(Subscription.topics("ready"), Serde.string, Serde.string)
            .mapZIO { record =>
              ZIO.attempt {
                record.value.fromJson[CoffeeOrder] match
                  case Right(order) => 
                    println(s"[CoffeeBar] Received ready order: ${order.orderId} for ${order.name}")
                    orders.put(order.orderId, order)
                  case Left(error) => 
                    println(s"[CoffeeBar] Failed to parse order: $error")
              }
            }
            .runDrain

        Unsafe.unsafe { implicit u =>
          Runtime.default.unsafe.fork(consumeEffect)
        }

        def getOrder(orderId: String): Task[Option[CoffeeOrder]] =
          ZIO.succeed {
            val result = orders.remove(orderId)
            println(s"[CoffeeBar] Pickup request for $orderId: ${if (result.isDefined) "Found" else "Not found"}")
            result
          }
        
        def isReady(orderId: String): Task[Boolean] =
          ZIO.succeed {
            val ready = orders.contains(orderId)
            println(s"[CoffeeBar] Ready check for $orderId: ${if (ready) "Ready" else "Not ready"}")
            ready
          }
    }
