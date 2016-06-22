package build.unstable.sonicd.auth

import java.net.InetAddress

import build.unstable.sonicd.model.JsonProtocol._
import spray.json._

import scala.util.Try

case class RequestContext(user: String, authorization: Int, mode: ApiKey.Mode, allowedIps: Option[List[InetAddress]]) {
}

object RequestContext {
  def fromJWTClaims(verifiedClaims: java.util.Map[String, AnyRef]): Try[RequestContext] = Try {
    ApiKey.Mode(verifiedClaims.get("mode").asInstanceOf[String]).flatMap { mode ⇒
      Try {
        RequestContext(
          verifiedClaims.get("user").asInstanceOf[String],
          verifiedClaims.get("authorization").asInstanceOf[String].toInt,
          mode,
          Option(verifiedClaims.get("from").asInstanceOf[String]).map(_.parseJson.convertTo[List[InetAddress]])
        )
      }
    }
  }.flatten
}
