import java.util.concurrent.LinkedBlockingQueue

import org.http4s._
import org.http4s.server.blaze.BlazeBuilder
import org.http4s.websocket.WebsocketBits._
import org.http4s.dsl._
import org.http4s.server.websocket._

import scala.concurrent.Await
import scala.concurrent.duration._

import scalaz.concurrent.Task
import scalaz.concurrent.Strategy
import org.http4s.client.blaze._
import scalaz.stream.{Process, Sink}
import scalaz.stream.{DefaultScheduler, Exchange}
import scalaz.stream.time.awakeEvery
import scalaz.stream.async.unboundedQueue
import spray.caching.{Cache, LruCache}
import scala.concurrent.ExecutionContext.Implicits.global
import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.json4s.jackson.JsonMethods._
import org.json4s.JsonDSL._
import scalaz.stream.wye

object BlazeWebSocketExample extends App {

  val client = PooledHttp1Client()

  val SOVTIMERS = "https://public-crest.eveonline.com/sovereignty/campaigns/"
  val cache: Cache[String] = LruCache[String](timeToLive = 30 seconds)
  def getLatestCrest(u: String): String = {
    val r = cache(u) {
      client.getAs[String](u).run
    }
    Await.result(r, 30.seconds)
  }

  val route = HttpService {
    case GET -> Root / "hello" =>
      Ok("Hello world.")

    case GET -> Root / "sov" =>
      println("connecting!")
      val q = new LinkedBlockingQueue[String]()
      val stream = awakeEvery(30 seconds)(Strategy.DefaultStrategy, DefaultScheduler).map{ d =>
        println("streaming!")
        q.size() match {
          case 0 =>
            val initial = getLatestCrest(SOVTIMERS)
            q.put(initial)
            Text(compact(render(("initial" -> parse(initial)))))
          case _ =>
            val current = getLatestCrest(SOVTIMERS)
            val old = parse(q.poll())
            q.put(current)
            val Diff(changed, added, deleted) = parse(current) diff old
            println(changed)
            println(added)
            println(deleted)
            Text(compact(render(("changed" -> changed) ~ ("added" -> added) ~ ("deleted" -> deleted))))
        }
      }
      val src: Process[Task, Text] = stream
      val sink: Sink[Task, WebSocketFrame] = Process.constant {
        case Text(t, _) => Task.delay( println(t))
        case f       => Task.delay(println(s"Unknown type: $f"))
      }
      WS(Exchange(src, sink))

    case req@ GET -> Root / "ws" =>
      val src = awakeEvery(1.seconds)(Strategy.DefaultStrategy, DefaultScheduler).map{ d => Text(s"Ping! $d") }
      val sink: Sink[Task, WebSocketFrame] = Process.constant {
        case Text(t, _) => Task.delay( println(t))
        case f       => Task.delay(println(s"Unknown type: $f"))
      }
      WS(Exchange(src, sink))
  }

  val server = BlazeBuilder.bindHttp(8090)
    .withWebSockets(true)
    .mountService(route, "/http4s")
    .start

  println("running bound to localhost:8090/http4s")
  server.run.awaitShutdown()
}