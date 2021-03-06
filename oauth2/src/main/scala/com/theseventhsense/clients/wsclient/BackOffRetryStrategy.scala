package com.theseventhsense.clients.wsclient

import akka.actor.ActorSystem
import com.theseventhsense.oauth2.OAuth2Service
import com.theseventhsense.utils.logging.Logging
import com.theseventhsense.utils.retry.FutureBackOffRetryStrategy
import play.api.libs.ws.WSResponse

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

sealed trait RetryFlags {
  def shouldRetry(response: WSResponse): Boolean
}

object RetryFlags {
  case object Http401 extends RetryFlags {
    def shouldRetry(response: WSResponse): Boolean = {
      response.status == 401
    }
  }

  case object Http409 extends RetryFlags {
    def shouldRetry(response: WSResponse): Boolean = {
      response.status == 409
    }
  }

  case object Http429 extends RetryFlags {
    def shouldRetry(response: WSResponse): Boolean = {
      response.status == 429
    }
  }

  case object Http500 extends RetryFlags {
    def shouldRetry(response: WSResponse): Boolean = {
      response.status == 500
    }
  }

  case object Http502 extends RetryFlags {
    def shouldRetry(response: WSResponse): Boolean = {
      response.status == 502
    }
  }

  case object Http503 extends RetryFlags {
    def shouldRetry(response: WSResponse): Boolean = {
      response.status == 503
    }
  }

  case object Http504 extends RetryFlags {
    def shouldRetry(response: WSResponse): Boolean = {
      response.status == 504
    }
  }

}

object BackOffRetryStrategy {

  class RetryableException(flags: Set[RetryFlags]) extends Throwable {
    override def getMessage: String = s"Retryable failure: $flags"
  }

  def shouldRetryThrowable(t: Throwable): Boolean = t match {
    case _: OAuth2Service.BadRequestError => false
    case _: OAuth2Service.DecodeError     => false
    case _                                => true
  }

}

class BackOffRetryStrategy(
  flags: Set[RetryFlags],
  firstDelay: FiniteDuration,
  maxCount: Int = 10,
  maxDelay: FiniteDuration = 1.hour,
  shouldRetryThrowable: Throwable => Boolean =
    BackOffRetryStrategy.shouldRetryThrowable
)(implicit system: ActorSystem, ec: ExecutionContext)
    extends RestClientRetryStrategy
    with Logging {

  import BackOffRetryStrategy._

  val genericRetryStrategy =
    new FutureBackOffRetryStrategy(
      firstDelay,
      maxCount,
      maxDelay,
      shouldRetry = shouldRetryThrowable
    )

  protected def shouldRetryResponse(response: WSResponse): Future[WSResponse] = {
    val shouldFlags = flags.filter(_.shouldRetry(response))
    if (shouldFlags.isEmpty) {
      Future.successful(response)
    } else {
      Future.failed(new RetryableException(shouldFlags))
    }
  }

  override def retry(producer: => Future[WSResponse]): Future[WSResponse] =
    genericRetryStrategy.retry(producer.flatMap(shouldRetryResponse))

}
