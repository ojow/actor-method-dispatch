package ojow.actor

import akka.actor.{Actor, ActorRef, Status}
import akka.pattern.ask
import akka.util.Timeout

import scala.concurrent.Future

trait Reply[+T] {

  def value: T

  def actorRef: ActorRef

  def methodName: String

  def params: ActorMethod.ParamsCollection[Any]

  def toFuture(implicit timeout: Timeout): Future[T] = OAMR[T](actorRef, methodName, params).runAsFuture()

  def handleReply(receiver: Receiver[T])(implicit sender: ActorRef = Actor.noSender): Unit =
    OAMR[T](actorRef, methodName, params).withReplyHandler(receiver).run()

  def handleReplyAndExceptions(receiver: Receiver[T], exceptionHandler: Receiver[Status.Failure])(implicit sender:
                                                                                      ActorRef = Actor.noSender): Unit =
    OAMR[T](actorRef, methodName, params).withReplyHandler(receiver).withExceptionHandler(exceptionHandler).run()

  def ignoreReply()(implicit sender: ActorRef = Actor.noSender): Unit =
    OAMR[T](actorRef, methodName, params).ignoreReply().run()

}

object Reply {

  def apply[T](v: T): Reply[T] = new Reply[T] with ReplyStub[T] {

    override def value = v

  }

}


trait ReplyStub[+T] extends Reply[T] {

  override def actorRef: ActorRef = error

  override def methodName: String = error

  override def params: ActorMethod.ParamsCollection[Any] = error

  override def toFuture(implicit timeout: Timeout): Future[T] = error

  override def handleReply(receiver: Receiver[T])(implicit sender: ActorRef = Actor.noSender): Unit = error

  override def handleReplyAndExceptions(receiver: Receiver[T], exceptionHandler: Receiver[Status.Failure])(implicit
                                                                        sender: ActorRef = Actor.noSender): Unit = error

  override def ignoreReply()(implicit sender: ActorRef = Actor.noSender): Unit = error

  private def error = throw new RuntimeException("The method can only be used on a Reply created by a proxy.")

}


object WillReplyLater extends Reply[Nothing] with ReplyStub[Nothing] {

  override def value = throw new RuntimeException("No value.")

}


