package dev.gertjanassies.common

import zio.*
import zio.json.*

trait CoffeeMenu:
  def list: UIO[List[Coffee]]
  def find(name: String): UIO[Option[Coffee]]

object CoffeeMenu:
  def list: URIO[CoffeeMenu, List[Coffee]]              = ZIO.serviceWithZIO(_.list)
  def find(name: String): URIO[CoffeeMenu, Option[Coffee]] = ZIO.serviceWithZIO(_.find(name))

  val live: ULayer[CoffeeMenu] = ZLayer.fromZIO(
    for
      raw    <- ZIO.attemptBlocking(
                  scala.io.Source.fromResource("coffees.json").mkString
                ).orDie
      coffees <- ZIO.fromEither(raw.fromJson[List[Coffee]])
                   .mapError(msg => new RuntimeException(s"Failed to parse coffees.json: $msg"))
                   .orDie
    yield CoffeeMenuLive(coffees)
  )

private final class CoffeeMenuLive(coffees: List[Coffee]) extends CoffeeMenu:
  private val index: Map[String, Coffee] = coffees.map(c => c.name -> c).toMap

  def list: UIO[List[Coffee]]              = ZIO.succeed(coffees)
  def find(name: String): UIO[Option[Coffee]] = ZIO.succeed(index.get(name))
