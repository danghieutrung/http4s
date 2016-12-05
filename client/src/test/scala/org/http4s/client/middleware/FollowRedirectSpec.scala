package org.http4s
package client
package middleware

import java.util.concurrent.atomic._

import org.http4s.dsl._
import org.http4s.headers._
import org.specs2.mutable.Tables
// import scalaz._
// import scalaz.concurrent._
// import scalaz.stream.Process._
// import scalaz.syntax.monad._
// import scodec.bits.ByteVector
import fs2._
import fs2.Stream._
import fs2.Task._
// import fs2.interop.cats._
// import cats.implicits._
// import cats.data._
// import cats._

class FollowRedirectSpec extends Http4sSpec with Tables {

  private val loopCounter = new AtomicInteger(0)

  val service = HttpService {
    case req @ _ -> Root / "ok" =>
      Ok(req.body).putHeaders(
        Header("X-Original-Method", req.method.toString),
        Header("X-Original-Content-Length", req.headers.get(`Content-Length`).fold(0L)(_.length).toString)
      )
    case req @ _ -> Root / status =>
      Response(status = Status.fromInt(status.toInt).yolo)
        .putHeaders(Location(uri("/ok")))
        .pure[Task]
  }

  val defaultClient = Client.fromHttpService(service)
  val client = FollowRedirect(3)(defaultClient)

  case class RedirectResponse(
    method: String,
    body: String
  )

  "FollowRedirect" should {
    "follow the proper strategy" in {
      def doIt(
                method: Method,
                status: Status,
                body: String,
                pure: Boolean,
                response: Either[Throwable, RedirectResponse]
              ) = {

        val u = uri("http://localhost") / status.code.toString
        val req = method match {
          case _: Method.PermitsBody if body.nonEmpty =>
            val bodyBytes = body.getBytes.toList
            Request(method, u,
              body =
                if (pure) Stream.emits(bodyBytes)
                else Stream.emits(bodyBytes)
              )
          case _ =>
            Request(method, u)
        }
        client.fetch(req) {
          case Ok(resp) =>
            val method = resp.headers.get("X-Original-Method".ci).fold("")(_.value)
            val body = resp.as[String]
            body.map(RedirectResponse(method, _))
          case resp =>
            Task.fail(UnexpectedStatus(resp.status))
          }.unsafeAttemptRun() must_== (response)
      }

      "method" | "status"          | "body"    | "pure" | "response"                               |>
      GET      ! MovedPermanently  ! ""        ! true   ! Right(RedirectResponse("GET", ""))         |
      HEAD     ! MovedPermanently  ! ""        ! true   ! Right(RedirectResponse("HEAD", ""))        |
      POST     ! MovedPermanently  ! "foo"     ! true   ! Right(RedirectResponse("GET", ""))         |
      POST     ! MovedPermanently  ! "foo"     ! false  ! Right(RedirectResponse("GET", ""))         |
      PUT      ! MovedPermanently  ! ""        ! true   ! Right(RedirectResponse("PUT", ""))         |
       PUT      ! MovedPermanently  ! "foo"     ! true   ! Right(RedirectResponse("PUT", "foo"))      |
      PUT      ! MovedPermanently  ! "foo"     ! false  ! Left(UnexpectedStatus(MovedPermanently))  |
      GET      ! Found             ! ""        ! true   ! Right(RedirectResponse("GET", ""))         |
      HEAD     ! Found             ! ""        ! true   ! Right(RedirectResponse("HEAD", ""))        |
      POST     ! Found             ! "foo"     ! true   ! Right(RedirectResponse("GET", ""))         |
      POST     ! Found             ! "foo"     ! false  ! Right(RedirectResponse("GET", ""))         |
      PUT      ! Found             ! ""        ! true   ! Right(RedirectResponse("PUT", ""))         |
       PUT      ! Found             ! "foo"     ! true   ! Right(RedirectResponse("PUT", "foo"))      |
      PUT      ! Found             ! "foo"     ! false  ! Left(UnexpectedStatus(Found))             |
      GET      ! SeeOther          ! ""        ! true   ! Right(RedirectResponse("GET", ""))         |
      HEAD     ! SeeOther          ! ""        ! true   ! Right(RedirectResponse("HEAD", ""))        |
      POST     ! SeeOther          ! "foo"     ! true   ! Right(RedirectResponse("GET", ""))         |
      POST     ! SeeOther          ! "foo"     ! false  ! Right(RedirectResponse("GET", ""))         |
      PUT      ! SeeOther          ! ""        ! true   ! Right(RedirectResponse("GET", ""))         |
      PUT      ! SeeOther          ! "foo"     ! true   ! Right(RedirectResponse("GET", ""))         |
      PUT      ! SeeOther          ! "foo"     ! false  ! Right(RedirectResponse("GET", ""))         |
      GET      ! TemporaryRedirect ! ""        ! true   ! Right(RedirectResponse("GET", ""))         |
      HEAD     ! TemporaryRedirect ! ""        ! true   ! Right(RedirectResponse("HEAD", ""))        |
       POST     ! TemporaryRedirect ! "foo"     ! true   ! Right(RedirectResponse("POST", "foo"))     |
      POST     ! TemporaryRedirect ! "foo"     ! false  ! Left(UnexpectedStatus(TemporaryRedirect)) |
      PUT      ! TemporaryRedirect ! ""        ! true   ! Right(RedirectResponse("PUT", ""))         |
       PUT      ! TemporaryRedirect ! "foo"     ! true   ! Right(RedirectResponse("PUT", "foo"))      |
      PUT      ! TemporaryRedirect ! "foo"     ! false  ! Left(UnexpectedStatus(TemporaryRedirect)) |
      GET      ! PermanentRedirect ! ""        ! true   ! Right(RedirectResponse("GET", ""))         |
      HEAD     ! PermanentRedirect ! ""        ! true   ! Right(RedirectResponse("HEAD", ""))        |
       POST     ! PermanentRedirect ! "foo"     ! true   ! Right(RedirectResponse("POST", "foo"))     |
      POST     ! PermanentRedirect ! "foo"     ! false  ! Left(UnexpectedStatus(PermanentRedirect)) |
      PUT      ! PermanentRedirect ! ""        ! true   ! Right(RedirectResponse("PUT", ""))         |
       PUT      ! PermanentRedirect ! "foo"     ! true   ! Right(RedirectResponse("PUT", "foo"))      |
      PUT      ! PermanentRedirect ! "foo"     ! false  ! Left(UnexpectedStatus(PermanentRedirect)) |
      { (method, status, body, pure, response) => doIt(method, status, body, pure, response) }
    }

    "Strip payload headers when switching to GET" in {
      // We could test others, and other scenarios, but this was a pain.
      val req = Request(PUT, uri("http://localhost/303")).withBody("foo")
      client.fetch(req) {
        case Ok(resp) =>
          resp.headers.get("X-Original-Content-Length".ci).map(_.value).pure[Task]
      }.unsafeRun() must be(Right(Some("0")))
    }

    "Not redirect more than 'maxRedirects' iterations" in {
      val statefulService = HttpService {
        case GET -> Root / "loop" =>
          val body = loopCounter.incrementAndGet.toString
          MovedPermanently(uri("/loop")).withBody(body)
      }
      val client = FollowRedirect(3)(Client.fromHttpService(statefulService))
      Stream.eval{client.fetch(GET(uri("http://localhost/loop"))) {
        case MovedPermanently(resp) => resp.as[String].map(_.toInt)
        case _ => Task.now(-1)
      }}.runLog.unsafeRun() must_==(4)
    }
  }
}
