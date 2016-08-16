import org.http4s.HttpService
import org.http4s.dsl.{Root, _}
import org.http4s.server.blaze.BlazeBuilder
import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods._
import org.scalatest.{FlatSpec, MustMatchers}

class WaterslideServerSpec extends FlatSpec with MustMatchers {

  def mockServer = {
    var a = 0
    val resource = HttpService {
      case req @ GET -> Root =>
        a = a + 1
        Ok(compact(render("value" -> a)))
    }
    val server = BlazeBuilder.bindHttp(9001).mountService(resource, "/").start
    server
  }

  "WaterslideServer" should "accept JSON every X seconds and stream it" in {
    val s  = mockServer.run
    val ws = new WaterslideServer("localhost", 9000, "http://localhost:9001/", 1, None)
    ws.streamIt.take(2).runLog.run must equal(
        List(
            """{"initial":{"value":1}}""",
            """{"diff":[{"op":"replace","path":"/value","value":2}]}"""
        )
    )
  }

}
