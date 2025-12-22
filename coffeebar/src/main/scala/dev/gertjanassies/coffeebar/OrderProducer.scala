package dev.gertjanassies.coffeebar

import dev.gertjanassies.common.CoffeeOrder
import zio.*
import zio.kafka.producer.*
import zio.kafka.serde.Serde
import zio.json.*
import org.apache.kafka.clients.producer.ProducerRecord

trait OrderProducer:
  def publishOrder(order: CoffeeOrder): Task[Unit]

object OrderProducer:
  def publishOrder(order: CoffeeOrder): ZIO[OrderProducer, Throwable, Unit] =
    ZIO.serviceWithZIO[OrderProducer](_.publishOrder(order))

  def live: ZLayer[Producer, Nothing, OrderProducer] =
    ZLayer.fromFunction { (producer: Producer) =>
      new OrderProducer:
        def publishOrder(order: CoffeeOrder): Task[Unit] =
          val record = new ProducerRecord[String, String](
            "order",
            order.orderId,
            order.toJson
          )
          producer.produce(record, Serde.string, Serde.string).unit
    }
