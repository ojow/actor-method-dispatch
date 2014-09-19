package akka.actor

import scala.concurrent.Future


trait ActorMethods {
  
  type ActorState <: Actor

  protected def thisActor: ActorState

  protected implicit def self: ActorRef

}


abstract class ActorRefWithMethods(val actorRef: ActorRef) extends ActorMethods with Serializable


case class ActorMethodCall(methodName: String, args: List[List[Any]], replyTo: Option[CurriedActorMethodCall[_]] = None)


class CurriedActorMethodCall[-T](val methodName: String, val args: List[List[Any]]) extends Serializable {

  def uncurry(value: T) = ActorMethodCall(methodName, args :+ List(value))

}

object DontReply extends CurriedActorMethodCall[Any]("", Nil)


trait Reply[+T] {

  def value: T

  def handleWith(method: CurriedActorMethodCall[T])(implicit sender: ActorRef)

  def toFuture: Future[T]

  def ignoreReply(): Unit

}

object Reply {

  def apply[T](v: T) = new Reply[T] with ReplyStub[T] {

    override def value = v

  }

}


trait ReplyStub[+T] extends Reply[T] {

  override def handleWith(method: CurriedActorMethodCall[T])(implicit sender: ActorRef) = error

  override def toFuture: Future[T] = error

  override def ignoreReply(): Unit = error

  private def error = throw new RuntimeException("Don't call this method inside the actor which created the ActorReply.")

}


object WillReplyLater extends Reply[Nothing] with ReplyStub[Nothing] {

  override def value = throw new RuntimeException("No value.")

}


class ReplyAddress[-T](val actorRef: ActorRef, val call: Option[CurriedActorMethodCall[T]] = None) {

  def sendReply(value: T)(implicit sender: ActorRef = Actor.noSender): Unit = {
    if (call != Some(DontReply)) {
      actorRef ! call.map(_.uncurry(value)).getOrElse(value)
    }
  }

}

object ReplyAddress {

  implicit def noReplyAddressInsideProxies[T]: ReplyAddress[T] = new ReplyAddress[Any](null)

}
