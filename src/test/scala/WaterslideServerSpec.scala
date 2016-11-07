import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

import org.http4s.{HttpService, Response}
import org.http4s.dsl.{Root, _}
import org.http4s.server.blaze.BlazeBuilder
import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods._
import org.scalatest.{FlatSpec, MustMatchers}

import scala.collection.concurrent.TrieMap
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scalaz.stream._
import scalaz.concurrent.Task

class WaterslideServerSpec extends FlatSpec with MustMatchers {

  def mockServer(port: Int) = {
    var a = 0
    val resource = HttpService {
      case req @ GET -> Root =>
        a = a + 1
        Ok(compact(render("value" -> a)))
    }
    val server = BlazeBuilder.bindHttp(port).mountService(resource, "/").start
    server
  }

  def mockBadServer(port: Int) = {
    var a = 0
    val resource = HttpService {
      case req @ GET -> Root =>
        a = a + 1
        a match {
          case i if i < 2  => Ok(compact(render("value" -> a)))
          case i if i == 2 => InternalServerError("<html>oh no</html>")
          case i if i > 2  => Ok(compact(render("value" -> a)))
        }
    }
    val server = BlazeBuilder.bindHttp(port).mountService(resource, "/").start
    server
  }

  def mockVeryBadServer(port: Int) = {
    var a = 0
    val resource = HttpService {
      case req @ GET -> Root =>
        a = a + 1
        a match {
          case i if i < 2  => Ok(compact(render("value" -> a)))
          case i if i >= 2 => InternalServerError("<html>oh no</html>")
        }
    }
    val server = BlazeBuilder.bindHttp(port).mountService(resource, "/").start
    server
  }

  "WaterslideServer" should "accept JSON every X seconds and stream it" in {
    val s  = mockServer(9500).run
    val ws = new WaterslideServer("localhost", 9501, "http://localhost:9500/", 1, None)
    ws.streamIt.take(2).runLog.run must equal(
        List(
            """{"initial":{"value":1}}""",
            """{"diff":[{"op":"replace","path":"/value","value":2}]}"""
        )
    )
  }

  "WaterslideServer" should "multiplex to multiple streams" in {
    val s       = mockServer(9502).run
    val ws      = new WaterslideServer("localhost", 9503, "http://localhost:9502/", 2, None)
    val stream1 = ws.streamIt
    val stream2 = ws.streamIt
    val merged  = wye(stream1, stream2)(wye.mergeHaltBoth)
    val expectedResults = List(
        """{"initial":{"value":1}}""",
        """{"diff":[{"op":"replace","path":"/value","value":2}]}""",
        """{"diff":[{"op":"replace","path":"/value","value":3}]}"""
    )
    import scala.concurrent.ExecutionContext.Implicits.global
    val r1 = Future { stream1.take(3).runLog.run }
    val r2 = Future { stream2.take(3).runLog.run }

    val (results1 :: results2 :: _) = Await.result(Future.sequence(List(r1, r2)), 15 seconds)
    results1 must equal(results2)
    results1 must equal(expectedResults)
  }

  "WaterslideServer" should "cope with bad responses" in {
    val s  = mockBadServer(9504).run
    val ws = new WaterslideServer("localhost", 9505, "http://localhost:9504/", 1, None)
    ws.streamIt.take(2).runLog.run must equal(
        List(
            """{"initial":{"value":1}}""",
            """{"diff":[{"op":"replace","path":"/value","value":3}]}"""
        )
    )
  }

  "WaterslideServer" should "serve cached data if all current responses are bad, but it had a response once" in {
    val s  = mockVeryBadServer(9506).run
    val ws = new WaterslideServer("localhost", 9507, "http://localhost:9506/", 1, None)
    ws.streamIt.take(1).runLog.run must equal(
        List(
            """{"initial":{"value":1}}"""
        )
    )
  }
}
