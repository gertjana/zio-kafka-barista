package dev.gertjanassies.common

import zio.json.*

case class CoffeeOrder(
  name: String,
  coffeeType: String,
  orderId: String
)

object CoffeeOrder:
  given JsonCodec[CoffeeOrder] = DeriveJsonCodec.gen[CoffeeOrder]
