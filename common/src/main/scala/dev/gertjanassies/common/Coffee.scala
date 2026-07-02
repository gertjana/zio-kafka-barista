package dev.gertjanassies.common

import zio.json.*

case class Coffee(
    name: String,
    displayName: String,
    description: String,
    volumeMl: Int,
    calories: Int,
    vegan: Boolean,
    glutenFree: Boolean,
    dairyFree: Boolean,
    caffeinated: Boolean
)

object Coffee:
  given JsonCodec[Coffee] = DeriveJsonCodec.gen[Coffee]
