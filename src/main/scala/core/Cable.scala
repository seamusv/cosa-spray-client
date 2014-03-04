package core

import akka.actor.ActorSystem
import akka.dispatch.Futures
import akka.io.IO
import akka.pattern.ask
import akka.util.Timeout
import spray.client.pipelining._
import spray.can.Http
import spray.http._
import spray.http.HttpHeaders.{`Set-Cookie`, Cookie}
import java.security.cert.X509Certificate
import javax.net.ssl.{KeyManager, X509TrustManager, SSLContext}
import scala.util.{Success, Failure}

object Cable extends App {

  implicit val trustfulSslContext: SSLContext = {
    object WideOpenX509TrustManager extends X509TrustManager {
      override def checkClientTrusted(chain: Array[X509Certificate], authType: String) = ()
      override def checkServerTrusted(chain: Array[X509Certificate], authType: String) = ()
      override def getAcceptedIssuers = Array[X509Certificate]()
    }

    val context = SSLContext.getInstance("TLS")
    context.init(Array[KeyManager](), Array(WideOpenX509TrustManager), null)
    context
  }

  implicit val system = ActorSystem("nwtel-cable-usage")
  implicit val timeout = Timeout(60000)

  import system.dispatcher

  def extractSessionCookies(httpResponse: HttpResponse) = httpResponse.headers.collect { case `Set-Cookie`(hc) => Cookie(hc) }

  val macAddress = "7CB21B9D69AE"
  val query = for {
    Http.HostConnectorInfo(connector, _) <- IO(Http) ? Http.HostConnectorSetup("apps.nwtel.ca", port = 443, sslEncryption = true)
    withoutSession <- Futures.successful { sendReceive(connector) }
    loginPage <- withoutSession(Get("/cable_usage/secured/index.jsp"))
    withSession <- Futures.successful { addHeaders(extractSessionCookies(loginPage)) ~> sendReceive(connector) }
    authenticate <- withSession(Post("/cable_usage/j_security_check", FormData(Map("j_target_url" -> "secured/index.jsp", "j_username" -> macAddress, "j_password" -> "123456", "MAC" -> macAddress, "submit_btn" -> "Submit"))))
    usage <- withSession(Get("/cable_usage/secured/index.jsp"))
  } yield usage

  query onComplete {
    case Success(httpResponse) =>
      println(httpResponse.entity.data.asString)
    case Failure(e) =>
      e.printStackTrace()
  }
}
