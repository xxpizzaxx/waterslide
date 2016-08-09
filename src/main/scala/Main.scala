import java.util.concurrent.LinkedBlockingQueue

import com.fasterxml.jackson.databind.ObjectMapper
import com.flipkart.zjsonpatch.JsonDiff
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
import scala.collection.JavaConverters._

object BlazeWebSocketExample extends App {

  val client = PooledHttp1Client()

  val jsonMapper = new ObjectMapper()

  val SOVTIMERS = "https://crest-tq.eveonline.com/sovereignty/campaigns/"
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
      val initialData = getLatestCrest(SOVTIMERS)
      q.put(initialData)
      val initialStream: Process[Task, Text] = Process.emit(Text(compact(render("initial" -> parse(initialData)))))
      val stream = awakeEvery(30 seconds)(Strategy.DefaultStrategy, DefaultScheduler).map{ d =>
        println("streaming!")
        val current = getLatestCrest(SOVTIMERS)
        val old = q.poll()
        q.put(current)
        val patch = JsonDiff.asJson(jsonMapper.readTree(old), jsonMapper.readTree(current))
        println(patch)
        Text(patch.toString)
      }.filter { f => f.str != "{}" }
      val src: Process[Task, Text] = initialStream ++ stream
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