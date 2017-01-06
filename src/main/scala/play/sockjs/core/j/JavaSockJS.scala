package play.sockjs.core.j

import scala.collection.JavaConverters._
import scala.concurrent.Future
import akka.stream.scaladsl._
import akka.stream.OverflowStrategy
import play.core.j.JavaHelpers
import play.mvc.Http.{Context => JContext}
import play.sockjs.api.{Frame, SockJS}
import play.sockjs.api.Frame._
import play.sockjs.api.SockJS._
import play.sockjs.api.libs.streams.ActorFlow
import play.sockjs.{Frame => JFrame}

import scala.compat.java8.FutureConverters

object JavaSockJS extends JavaHelpers {

  def run(retrieveSockJS: => play.sockjs.SockJS) = SockJS { request =>
    implicit val javaContext = createJavaContext(request)

    val javaSockJS = try {
      JContext.current.set(javaContext)
      retrieveSockJS
    } finally {
      JContext.current.remove()
    }

    javaSockJS match {
      case legacy: play.sockjs.LegacySockJS => JavaSockJS.legacySockjsWrapper(legacy)
      case sockjs => JavaSockJS.sockjsWrapper(sockjs)
    }
  }

  private def legacySockjsWrapper(javaSockJS: play.sockjs.LegacySockJS)(implicit ctx: play.mvc.Http.Context) = {

    val reject = Option(javaSockJS.rejectWith())
    Future.successful(reject.map { result =>
      Left(createResult(ctx, result))
    }.getOrElse {
      val app = play.api.Play.privateMaybeApplication.get
      implicit val system = app.actorSystem
      implicit val mat = app.materializer

      Right(
        if (javaSockJS.isActor) {
          MessageFlowTransformer.stringFrameFlowTransformer
            .transform(ActorFlow.actorRef(javaSockJS.actorProps, 256, OverflowStrategy.dropNew))
        } else {

          val socketIn = new play.sockjs.SockJS.In

          val sink = Flow[Frame].collect {
            case Text(data) =>
              data.foreach(msg => socketIn.callbacks.asScala.foreach(_.accept(msg)))
          }.to(Sink.onComplete { _ =>
            socketIn.closeCallbacks.asScala.foreach(_.run())
          })

          val source = Source.actorRef[Frame](256, OverflowStrategy.dropNew).mapMaterializedValue { actor =>
            val socketOut = new play.sockjs.SockJS.Out {
              def write(message: String): Unit = actor ! Frame.Text(message)
              def close(): Unit = actor ! Frame.Close.GoAway
            }

            javaSockJS.onReady(socketIn, socketOut)
          }

          Flow.fromSinkAndSource(sink, source)
        }
      )
    })
  }

  private def sockjsWrapper(sockjs: play.sockjs.SockJS)(implicit ctx: play.mvc.Http.Context) = {
    FutureConverters.toScala(sockjs(ctx.request())).map { resultOrFlow =>
      if (resultOrFlow.left.isPresent) {
        Left(resultOrFlow.left.get.asScala())
      } else {
        Right(Flow[Frame].mapConcat[JFrame] {
          case Frame.Text(texts) => texts.map(new JFrame.Text(_))
          case Frame.Close(code, reason) => List(new JFrame.Close(code, reason))
          case _ => List.empty
        }.via(resultOrFlow.right.get.asScala).map {
          case text: JFrame.Text => Frame.Text(text.data())
          case close: JFrame.Close => Frame.Close(close.code(), close.reason())
        })
      }
    }(play.api.libs.iteratee.Execution.trampoline)
  }
}