import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicReference

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

class WaterslideServer(hostname: String, port: Int, url: String, ttl: Int) {

  val client = PooledHttp1Client()

  val OM = new ObjectMapper()

  val cache: Cache[String] = LruCache[String](timeToLive = ttl.seconds)

  def getLatestCrest(u: String): String = {
    val r = cache(u) {
      client.getAs[String](u).run
    }
    Await.result(r, ttl.seconds)
  }

  val route = HttpService {
    case GET -> Root =>
      val pings = awakeEvery(10 seconds)(Strategy.DefaultStrategy, DefaultScheduler).map { _ => Ping() }
      val src = awakeEvery(1 second)(Strategy.DefaultStrategy, DefaultScheduler).map { _ =>
        getLatestCrest(url) // this function is memoized
      }.zipWithPrevious.filter {
        case (x, y) => !x.contains(y) // deduplicate
      }.map {
        // transform to JSON
        case (None, current) => compact(render("initial" -> parse(current))) // first run!
        case (Some(prev), current) => // we've had a change in the JSON
          val diffs = JsonDiff.asJson(OM.readTree(prev), OM.readTree(current)).toString
          compact(render("diff" -> parse(diffs)))
      }.map { x => Text(x) } // shove it in a websocket frame
    val sink: Sink[Task, WebSocketFrame] = Process.constant {
      case Text(t, _) => Task.delay(println(t))
      case f => Task.delay(println(s"Unknown type: $f"))
    }
      val joinedOutput = wye(pings, src)(wye.mergeHaltR)
      WS(Exchange(joinedOutput, sink))
  }

  val server = BlazeBuilder.bindHttp(host = "localhost", port = port)
    .withWebSockets(true)
    .mountService(route, "/")
    .start

}