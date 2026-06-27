package dev.gertjanassies.common

import zio.*

object Defaults {
  final val SIMULATE_WORK_DURATION = 2.seconds
  final val ORDER_TOPIC: String = "order"
  final val ORDER_TAKEN_TOPIC: String = "taken"
  final val ORDER_PREPARED_TOPIC: String = "prepared"
  final val ORDER_READY_TOPIC: String = "ready"
}
