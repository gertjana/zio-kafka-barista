package dev.gertjanassies.coffeebar

import dev.gertjanassies.common.CoffeeOrder
import zio.*
import zio.http.*
import zio.json.*
import zio.logging.backend.SLF4J
import java.util.UUID

object CoffeeBarApp extends ZIOAppDefault:
  
  val orderRoute = Routes(
    Method.POST / "order" -> handler { (req: Request) =>
      (for
        body <- req.body.asString
        orderRequest <- ZIO.fromEither(body.fromJson[OrderRequest])
          .mapError(err => Response.badRequest(err))
        orderId = UUID.randomUUID().toString
        order = CoffeeOrder(orderRequest.name, orderRequest.coffeeType, orderId)
        _ <- OrderProducer.publishOrder(order)
        responseBody = s"{\"orderId\":\"$orderId\",\"status\":\"Order placed\"}"
      yield Response
        .status(Status.Accepted)
        .addHeader(Header.Location(URL.decode(s"/check/$orderId").toOption.get))
        .copy(body = Body.fromString(responseBody))
      ).mapError(_ => Response.internalServerError)
    },
    
    Method.GET / "check" / string("orderId") -> handler { (orderId: String, _: Request) =>
      (for
        ready <- PreparedConsumer.isReady(orderId)
        response <- if ready then
          ZIO.succeed(
            Response
              .seeOther(URL.decode(s"/pickup/$orderId").toOption.get)
              .addHeader(Header.Location(URL.decode(s"/pickup/$orderId").toOption.get))
          )
        else
          ZIO.succeed(Response.ok)
      yield response).mapError(_ => Response.internalServerError)
    },
    
    Method.GET / "pickup" / string("orderId") -> handler { (orderId: String, _: Request) =>
      (for
        maybeOrder <- PreparedConsumer.getOrder(orderId)
        response <- maybeOrder match
          case Some(order) => 
            ZIO.succeed(Response.json(order.toJson))
          case None => 
            ZIO.succeed(Response.status(Status.NotFound))
      yield response).mapError(_ => Response.internalServerError)
    }
  )

  override def run =
    Server
      .serve(orderRoute)
      .provide(
        Runtime.removeDefaultLoggers >>> SLF4J.slf4j,
        Server.default,
        KafkaConfig.producerLayer,
        KafkaConfig.consumerLayer,
        OrderProducer.live,
        PreparedConsumer.live
      )

case class OrderRequest(name: String, coffeeType: String)

object OrderRequest:
  given JsonCodec[OrderRequest] = DeriveJsonCodec.gen[OrderRequest]
