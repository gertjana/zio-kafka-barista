package dev.gertjanassies.barista

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
          .withGroupId("barista-workers")
          .withGroupInstanceId(s"barista-${sys.env.getOrElse("BARISTA_ID", "local")}")
          .withProperties(Map("auto.offset.reset" -> "latest"))
      )
    )
