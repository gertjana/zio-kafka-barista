package dev.gertjanassies.coffeebar

import zio.*
import zio.kafka.producer.*
import zio.kafka.consumer.*

object KafkaConfig:
  private val bootstrapServers = sys.env.getOrElse("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092")
  
  val producerLayer: ZLayer[Any, Throwable, Producer] =
    ZLayer.scoped(
      Producer.make(
        ProducerSettings(List(bootstrapServers))
      )
    )

  val consumerLayer: ZLayer[Any, Throwable, Consumer] =
    ZLayer.scoped(
      Consumer.make(
        ConsumerSettings(List(bootstrapServers))
          .withGroupId("coffee-bar-pickup")
          .withGroupInstanceId("coffee-bar")
          .withProperties(Map("auto.offset.reset" -> "latest"))
      )
    )
